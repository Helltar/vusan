package com.helltar.vusan.tools.memory

import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.memoryOwner
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.llm.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.request.testChat
import com.helltar.vusan.tools.toolFailure
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class MemoryToolsTest {

    private lateinit var tempDir: Path
    private val memory = MemoryRepository()
    private val tools = MemoryTools(memory, requestContext(chatId = -100, isPrivate = false))

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-memory-tools-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a detail at the limit is saved whole`() = runBlocking {
        val detail = "a".repeat(500)

        tools.rememberAboutGroup(detail)

        assertEquals(listOf(detail), memory.load(testChat(-100).memoryOwner).map { it.content })
    }

    // an item that grows is appended to, so cutting it would drop the newest part without anyone noticing
    @Test
    fun `a detail over the limit is refused and the item it would replace is kept`() = runBlocking {
        val id = memory.add(testChat(-100).memoryOwner, "likes dark themes")

        val failure = toolFailure { tools.rememberAboutGroup("likes dark themes; " + "b".repeat(500), replacesId = id) }

        assertContains(failure, "519 characters")
        assertContains(failure, "nothing was saved")
        assertEquals(listOf("likes dark themes"), memory.load(testChat(-100).memoryOwner).map { it.content })
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxModelCalls = 70,
            accessPolicy = AccessPolicy(),
            appearance = null,
            databasePath = dbPath,
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            klipyApiKey = null,
            llmProvider = LlmProviderConfig.OpenAi(
                apiKey = "test",
                model = "test",
                requestTimeout = 60.seconds,
            ),
            maxConcurrentTurns = 4,
            image = null,
            openAiStt = null,
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
