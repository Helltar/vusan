package com.helltar.vusan.telegram

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.outbox.BotOutput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import com.helltar.vusan.request.AccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class PollRegistryTest {

    private lateinit var tempDir: Path
    private lateinit var registry: PollRegistry

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-polls-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        registry = PollRegistry(30.days)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a quiz is remembered with its options and the answer that counts`() = runBlocking {
        registry.remember("p1", chatId = -100L, output = quiz)

        val found = assertNotNull(registry.find("p1"))

        assertEquals(-100L, found.chatId)
        assertEquals(listOf("Lviv", "Kyiv", "Odesa"), found.options)
        assertEquals(1, found.correctOptionIndex)
    }

    @Test
    fun `an ordinary poll is remembered with no right answer`() = runBlocking {
        registry.remember("p2", chatId = -100L, output = BotOutput.Poll("Tea or coffee?", listOf("Tea", "Coffee")))

        val found = assertNotNull(registry.find("p2"))

        assertEquals(listOf("Tea", "Coffee"), found.options)
        assertNull(found.correctOptionIndex)
    }

    @Test
    fun `an answer to a poll nobody remembers finds nothing`() = runBlocking {
        assertNull(registry.find("never-sent"))
    }

    // telegram can deliver the same poll's creation twice through a redelivered update; the second
    // write is the same poll and must not fail or replace what is already there.
    @Test
    fun `remembering the same poll twice keeps one row`() = runBlocking {
        registry.remember("p1", chatId = -100L, output = quiz)
        registry.remember("p1", chatId = -100L, output = quiz)

        assertEquals(listOf("Lviv", "Kyiv", "Odesa"), assertNotNull(registry.find("p1")).options)
    }

    @Test
    fun `a poll old enough to be forgotten is dropped by the next one`() = runBlocking {
        val forgetful = PollRegistry(1.milliseconds)

        forgetful.remember("p1", chatId = -100L, output = quiz)
        // the prune runs on the way in, so a second poll is what clears the first — and it has to be
        // old enough by then, which is what the wait buys.
        delay(20)
        forgetful.remember("p2", chatId = -100L, output = quiz)

        assertNull(forgetful.find("p1"))
        assertNotNull(forgetful.find("p2"))
    }

    // an output that is neither leaves no row: there is no poll id it could ever be matched against.
    @Test
    fun `a plain message is not remembered as a poll`() = runBlocking {
        registry.remember("p1", chatId = -100L, output = BotOutput.Text("hello"))

        assertNull(registry.find("p1"))
    }

    private val quiz =
        BotOutput.Quiz(
            question = "Capital of Ukraine?",
            options = listOf("Lviv", "Kyiv", "Odesa"),
            correctOptionIndex = 1
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
