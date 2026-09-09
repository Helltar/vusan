package com.helltar.vusan.telegram

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import com.helltar.vusan.request.AccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.chat.Chat
import org.telegram.telegrambots.meta.api.objects.message.Message

class UpdateSpoolTest {

    private lateinit var tempDir: Path
    private lateinit var spool: UpdateSpool

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-spool-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        spool = UpdateSpool(30.minutes)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `an update the last run never picked up comes back whole`() = runBlocking {
        spool.record(listOf(update(id = 7, text = "what is the weather")))

        val replayed = spool.drain().single()

        assertEquals(7, replayed.updateId)
        assertEquals("what is the weather", replayed.message.text)
        assertEquals(-100_123L, replayed.message.chat.id)
        assertEquals(5L, replayed.message.from.id)
    }

    @Test
    fun `an update the dispatch loop took is not replayed`() = runBlocking {
        spool.record(listOf(update(id = 7), update(id = 8)))
        spool.settle(7)

        assertEquals(listOf(8), spool.drain().map { it.updateId })
    }

    @Test
    fun `replay is ordered the way telegram handed the updates over`() = runBlocking {
        spool.record(listOf(update(id = 31), update(id = 29), update(id = 30)))

        assertEquals(listOf(29, 30, 31), spool.drain().map { it.updateId })
    }

    // telegram redelivers what it could not confirm, so the same update can arrive again while its
    // spooled row is still there. the row is the same update either way.
    @Test
    fun `a redelivered update does not collide with its own spooled row`() = runBlocking {
        spool.record(listOf(update(id = 7, text = "first write")))
        spool.record(listOf(update(id = 7, text = "second write")))

        val replayed = spool.drain().single()

        assertEquals("first write", replayed.message.text)
    }

    @Test
    fun `a message too old to still answer is dropped instead`() = runBlocking {
        val shortLived = UpdateSpool(1.seconds)

        shortLived.record(listOf(update(id = 7)))

        assertTrue(shortLived.drain(now = Instant.now().plusSeconds(60)).isEmpty())
    }

    @Test
    fun `draining empties the spool so a second restart replays nothing`() = runBlocking {
        spool.record(listOf(update(id = 7)))

        spool.drain().forEach { spool.settle(it.updateId) }

        assertTrue(spool.drain().isEmpty())
    }

    private fun update(id: Int, text: String = "hello") =
        Update().apply {
            updateId = id
            message =
                Message.builder()
                    .messageId(77)
                    .text(text)
                    .date(1_700_000_000)
                    .chat(Chat.builder().id(-100_123L).type("supergroup").title("Test group").build())
                    .from(User.builder().id(5L).firstName("Ann").isBot(false).build())
                    .build()
        }

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
