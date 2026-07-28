package com.helltar.vusan.agent.history

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class ChatHistoryRepositoryTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-chat-history-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `clear advances only the users revision and removes their turns`() = runBlocking {
        val history = ChatHistoryRepository()
        history.appendTurns(42L, listOf(ChatTurn(ChatRole.USER, "first")))
        history.appendTurns(99L, listOf(ChatTurn(ChatRole.USER, "other")))

        assertEquals(0L, history.revision(42L))

        history.clear(42L)

        assertEquals(1L, history.revision(42L))
        assertTrue(history.load(42L).isEmpty())
        assertEquals(listOf("other"), history.load(99L).map { it.content })
        assertEquals(0L, history.revision(99L))

        history.clear(42L)

        assertEquals(2L, history.revision(42L))
    }

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
