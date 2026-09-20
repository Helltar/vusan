package com.helltar.vusan.tools.tasks

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.request.testUser
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tasks.TasksRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class FollowUpToolsTest {

    private lateinit var tempDir: Path
    private lateinit var repo: TasksRepository

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-follow-up-tools-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        repo = TasksRepository()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `follow-up is stored as a one-time task the bot set for itself`() = runBlocking {
        val tools = tools(requestContext(chatId = 100L, userId = 100L, messageId = "7"))

        assertContains(tools.scheduleFollowUp("ask how the exam went", tomorrow().toString()), "Follow-up id=")

        val stored = assertNotNull(repo.listForUser(testUser(100)).singleOrNull())
        assertTrue(stored.selfInitiated)
        assertIs<Recurrence.Once>(stored.recurrence)
        assertEquals("7", stored.creatorMessageId)
    }

    @Test
    fun `follow-ups and user-requested tasks are capped separately`() = runBlocking {
        val context = requestContext(chatId = 100L, userId = 100L)
        val tools = tools(context)
        val at = tomorrow()

        repeat(3) { assertContains(tools.scheduleFollowUp("check in $it", at.plusMinutes(it.toLong()).toString()), "Follow-up id=") }

        assertContains(tools.scheduleFollowUp("one more", at.plusHours(2).toString()), "limit 3")

        // the bot filling its own follow-up quota must not block what the user asks for
        val taskTools = TaskTools(repo = repo, context = context, maxTasksPerUser = 5)
        assertContains(taskTools.scheduleTask("send the news", "every 2h"), "Scheduled task id=")
    }

    @Test
    fun `follow-up in the past is rejected`() = runBlocking {
        val tools = tools(requestContext(chatId = 100L, userId = 100L))
        val past = Instant.now().atZone(ZoneId.systemDefault()).minusDays(1).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)

        assertContains(tools.scheduleFollowUp("too late", past.toString()), "in the past")
        assertTrue(repo.listForUser(testUser(100)).isEmpty())
    }

    private fun tools(context: RequestContext) =
        FollowUpTools(repo = repo, context = context, maxFollowUpsPerUser = 3)

    private fun tomorrow() =
        Instant.now().atZone(ZoneId.systemDefault()).plusDays(1).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)

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
                requestTimeout = 60.seconds,
            ),
            maxConcurrentTurns = 4,
            maxQueuedTurnsPerConversation = 3,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            regolithToken = null,
            regolithUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null,
        )
}
