package com.helltar.vusan.tools.tasks

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tasks.NewScheduledTask
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tasks.TasksRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class TaskToolsTest {

    private lateinit var tempDir: Path
    private lateinit var repo: TasksRepository

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-task-tools-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        repo = TasksRepository()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `pause and resume tools use the same persisted task state as the menu`() = runBlocking {
        val future = Instant.now().plusSeconds(3_600)
        val id = createTask(chatId = 100L, title = "hydrate", nextFireAt = future)
        val tools = tools(RequestContext(chatId = 100L, userId = 100L, messageId = 1L))

        assertContains(tools.pauseTask(id), "Paused task id=$id")
        assertTrue(assertNotNull(repo.findForUser(100L, id)).paused)

        assertContains(tools.resumeTask(id), "Resumed task id=$id")
        val resumed = assertNotNull(repo.findForUser(100L, id))
        assertFalse(resumed.paused)
        assertEquals(future.toEpochMilli(), resumed.nextFireAt.toEpochMilli())
    }

    @Test
    fun `edit tool changes selected fields and preserves schedule and pause`() = runBlocking {
        val future = Instant.now().plusSeconds(3_600)
        val id =
            createTask(
                chatId = 100L,
                title = "old title",
                recurrence = Recurrence.Every(1.hours),
                nextFireAt = future
            )
        repo.pauseForUser(100L, id)
        val tools = tools(RequestContext(chatId = 100L, userId = 100L, messageId = 1L))

        val result =
            tools.editTask(
                id = id,
                prompt = "send the revised report",
                title = ""
            )

        assertContains(result, "Updated task id=$id")
        val edited = assertNotNull(repo.findForUser(100L, id))
        assertEquals("send the revised report", edited.prompt)
        assertEquals(null, edited.title)
        assertEquals(Recurrence.Every(1.hours), edited.recurrence)
        assertEquals(future.toEpochMilli(), edited.nextFireAt.toEpochMilli())
        assertTrue(edited.paused)
    }

    @Test
    fun `edit tool replaces schedule and timezone`() = runBlocking {
        val beforeEdit = Instant.now()
        val id = createTask(chatId = 100L, title = "morning report")
        val tools = tools(RequestContext(chatId = 100L, userId = 100L, messageId = 1L))

        val result =
            tools.editTask(
                id = id,
                schedule = "cron 30 8 * * *",
                timezone = "Europe/Kyiv"
            )

        assertContains(result, "Updated task id=$id")
        val edited = assertNotNull(repo.findForUser(100L, id))
        assertEquals("Europe/Kyiv", edited.timezone.id)
        assertEquals("30 8 * * *", assertIs<Recurrence.Cron>(edited.recurrence).expression)
        assertTrue(edited.nextFireAt.isAfter(beforeEdit))
        assertFalse(edited.paused)
    }

    @Test
    fun `resume tool advances an elapsed recurring task`() = runBlocking {
        val beforeResume = Instant.now()
        val id =
            createTask(
                chatId = 100L,
                title = "hourly report",
                recurrence = Recurrence.Every(1.hours),
                nextFireAt = beforeResume.minusSeconds(10_800)
            )
        repo.pauseForUser(100L, id)
        val tools = tools(RequestContext(chatId = 100L, userId = 100L, messageId = 1L))

        assertContains(tools.resumeTask(id), "Resumed task id=$id")

        val resumed = assertNotNull(repo.findForUser(100L, id))
        assertFalse(resumed.paused)
        assertTrue(resumed.nextFireAt.isAfter(beforeResume))
    }

    @Test
    fun `resume tool leaves an elapsed one-time task paused`() = runBlocking {
        val id =
            createTask(
                chatId = 100L,
                title = "old reminder",
                nextFireAt = Instant.now().minusSeconds(60)
            )
        repo.pauseForUser(100L, id)
        val tools = tools(RequestContext(chatId = 100L, userId = 100L, messageId = 1L))

        val result = tools.resumeTask(id)

        assertContains(result, "cannot be resumed")
        assertTrue(assertNotNull(repo.findForUser(100L, id)).paused)
    }

    @Test
    fun `group task tools list and change only tasks from the current chat`() = runBlocking {
        val currentId = createTask(chatId = -200L, title = "group report")
        val otherId = createTask(chatId = -300L, title = "other group report")
        val tools =
            tools(
                RequestContext(
                    chatId = -200L,
                    userId = 100L,
                    messageId = 1L,
                    chatIsPrivate = false
                )
            )

        val listed = tools.listTasks()
        assertContains(listed, "group report")
        assertFalse(listed.contains("other group report"))

        assertContains(tools.pauseTask(otherId), "in this chat")
        assertFalse(assertNotNull(repo.findForUser(100L, otherId)).paused)

        assertContains(tools.editTask(otherId, title = "leaked title"), "in this chat")
        assertEquals("other group report", assertNotNull(repo.findForUser(100L, otherId)).title)

        assertContains(tools.pauseTask(currentId), "Paused task id=$currentId")
        assertTrue(assertNotNull(repo.findForUser(100L, currentId)).paused)

        assertContains(tools.cancelTask(otherId), "in this chat")
        assertNotNull(repo.findForUser(100L, otherId))
        Unit
    }

    private fun tools(context: RequestContext) =
        TaskTools(repo = repo, context = context, maxTasksPerUser = 5)

    private suspend fun createTask(
        chatId: Long,
        title: String,
        recurrence: Recurrence = Recurrence.Once,
        nextFireAt: Instant = Instant.now().plusSeconds(3_600)
    ): Long =
        repo.create(
            NewScheduledTask(
                userId = 100L,
                chatId = chatId,
                prompt = "run $title",
                title = title,
                recurrence = recurrence,
                timezone = ZoneId.of("UTC"),
                nextFireAt = nextFireAt,
                creatorMessageId = 1L,
                creatorUsername = "tester",
                creatorDisplayName = "Test User",
                chatIsPrivate = chatId > 0L,
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
