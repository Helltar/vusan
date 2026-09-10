package com.helltar.vusan.tasks

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.infra.Db
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.testChat
import com.helltar.vusan.request.testScope
import com.helltar.vusan.request.testUser
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class TasksRepositoryTest {

    private lateinit var tempDir: Path
    private lateinit var repo: TasksRepository

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-tasks-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        repo = TasksRepository()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `paused tasks stay listed and counted but are not due`() = runBlocking {
        val id = createTask(Instant.parse("2026-07-28T08:00:00Z"))

        assertEquals(listOf(id), repo.findDue(Instant.parse("2026-07-28T09:00:00Z")).map { it.id })

        assertTrue(repo.pauseForUser(testUser(100), id))

        assertTrue(repo.findDue(Instant.parse("2026-07-28T09:00:00Z")).isEmpty())
        assertEquals(1, repo.countEnabledByUser(testUser(100)))
        assertEquals(listOf(id), repo.listEnabledByUser(testUser(100)).map { it.id })
    }

    @Test
    fun `editing a title does not overwrite a concurrent reschedule`() = runBlocking {
        val id = createTask(Instant.parse("2026-07-28T08:00:00Z"))
        val original = assertNotNull(repo.findEnabledForUser(testUser(100), id))
        val schedulerNextFire = Instant.parse("2026-07-29T08:00:00Z")

        repo.reschedule(id, schedulerNextFire)
        assertTrue(repo.editEnabledForUser(testUser(100), original, original.copy(title = "renamed")))

        val stored = assertNotNull(repo.findEnabledForUser(testUser(100), id))
        assertEquals("renamed", stored.title)
        assertEquals(schedulerNextFire, stored.nextFireAt)
    }

    @Test
    fun `losing a chat parks every task scheduled there and nothing else`() = runBlocking {
        val due = Instant.parse("2026-07-28T09:00:00Z")
        val lostChat = -500L

        val first = createTask(Instant.parse("2026-07-28T08:00:00Z"), chatId = lostChat)
        val second = createTask(Instant.parse("2026-07-28T08:30:00Z"), chatId = lostChat)
        val elsewhere = createTask(Instant.parse("2026-07-28T08:45:00Z"), chatId = -600L)

        assertEquals(2, repo.pauseAllInChat(testChat(lostChat)))
        assertEquals(listOf(elsewhere), repo.findDue(due).map { it.id })

        // parked, not deleted: they stay listed so their owner can resume them if the bot gets back in.
        assertTrue(repo.listEnabledByUser(testUser(100)).map { it.id }.containsAll(listOf(first, second)))

        // a second removal notice for the same chat must not re-park what is already parked.
        assertEquals(0, repo.pauseAllInChat(testChat(lostChat)))
    }

    // a task is both an owner's quota and somebody else's chat, so an unqualified id would let one
    // platform's owner pause, edit and delete another's tasks.
    @Test
    fun `a task belongs to one platform's owner and chat`() = runBlocking {
        val repo = TasksRepository()
        val onTelegram = testScope(userId = 100, chatId = -200, platform = Platform.TELEGRAM)
        val onDiscord = testScope(userId = 100, chatId = -200, platform = Platform.DISCORD)

        val id = repo.create(newTask(onTelegram, "telegram digest"))
        repo.create(newTask(onDiscord, "discord digest"))

        assertNull(repo.findEnabledForUser(onDiscord.user, id))
        assertFalse(repo.pauseForUser(onDiscord.user, id))
        assertFalse(repo.deleteEnabledForUser(onDiscord.user, id))
        assertNotNull(repo.findEnabledForUser(onTelegram.user, id))

        assertEquals(listOf("telegram digest"), repo.listEnabledByUser(onTelegram.user).map { it.title })
        assertEquals(listOf("discord digest"), repo.listEnabledByUser(onDiscord.user).map { it.title })

        // the same chat number on the other platform is a different room, and parking it leaves this one running
        assertEquals(1, repo.pauseAllInChat(onDiscord.chat))
        assertFalse(assertNotNull(repo.findEnabledForUser(onTelegram.user, id)).paused)
    }

    private fun newTask(scope: ConversationScope, title: String) =
        NewScheduledTask(
            scope = scope,
            prompt = "run $title",
            title = title,
            recurrence = Recurrence.Once,
            timezone = ZoneId.of("UTC"),
            nextFireAt = Instant.parse("2026-07-28T12:00:00Z"),
            creatorMessageId = "1",
            creatorThreadId = null,
            creatorUsername = "tester",
            creatorDisplayName = "Test User",
            chatIsPrivate = false,
            language = Language.ENGLISH
        )

    private suspend fun createTask(nextFireAt: Instant, chatId: Long = 100L): Long =
        repo.create(
            NewScheduledTask(
                scope = testScope(userId = 100, chatId = chatId),
                prompt = "send reminder",
                title = "reminder",
                recurrence = Recurrence.Once,
                timezone = ZoneId.of("UTC"),
                nextFireAt = nextFireAt,
                creatorThreadId = null,
                creatorMessageId = "1",
                creatorUsername = "tester",
                creatorDisplayName = "Test User",
                chatIsPrivate = true,
                language = Language.ENGLISH
            )
        )

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            accessPolicy = AccessPolicy(),
            appearance = null,
            databasePath = dbPath,
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            llmProvider = LlmProviderConfig.Hosted(
                provider = HostedLlmProvider.OPENAI,
                apiKey = "test",
                model = "test",
                requestTimeout = 60.seconds
            ),
            maxFollowUpsPerUser = 3,
            maxMemoryPerScope = 10,
            maxTasksPerUser = 5,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            workspaceMaxTimeoutSeconds = 600L,
            workspaceToken = null,
            workspaceUrl = null,
            sitesToken = null,
            sitesUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
