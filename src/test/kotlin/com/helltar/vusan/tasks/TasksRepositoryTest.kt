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

        assertTrue(repo.pauseForUser(100L, id))

        assertTrue(repo.findDue(Instant.parse("2026-07-28T09:00:00Z")).isEmpty())
        assertEquals(1, repo.countEnabledByUser(100L))
        assertEquals(listOf(id), repo.listEnabledByUser(100L).map { it.id })
    }

    @Test
    fun `editing a title does not overwrite a concurrent reschedule`() = runBlocking {
        val id = createTask(Instant.parse("2026-07-28T08:00:00Z"))
        val original = assertNotNull(repo.findEnabledForUser(100L, id))
        val schedulerNextFire = Instant.parse("2026-07-29T08:00:00Z")

        repo.reschedule(id, schedulerNextFire)
        assertTrue(repo.editEnabledForUser(100L, original, original.copy(title = "renamed")))

        val stored = assertNotNull(repo.findEnabledForUser(100L, id))
        assertEquals("renamed", stored.title)
        assertEquals(schedulerNextFire, stored.nextFireAt)
    }

    private suspend fun createTask(nextFireAt: Instant): Long =
        repo.create(
            NewScheduledTask(
                userId = 100L,
                chatId = 100L,
                prompt = "send reminder",
                title = "reminder",
                recurrence = Recurrence.Once,
                timezone = ZoneId.of("UTC"),
                nextFireAt = nextFireAt,
                creatorMessageId = 1L,
                creatorUsername = "tester",
                creatorDisplayName = "Test User",
                chatIsPrivate = true,
                language = Language.ENGLISH
            )
        )

    private fun testConfig(dbPath: String) =
        AppConfig(
            allowedIds = emptySet(),
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
            sandboxTimeoutSeconds = 30L,
            sandboxUrl = null,
            searxngUrl = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
