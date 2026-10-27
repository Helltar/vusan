package com.helltar.vusan.agent.conversation

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.llm.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.testScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ConversationPlannerTest {

    private lateinit var tempDir: Path
    private val repository = ConversationRepository()
    private val compactor = RecordingCompactor()
    private val planner = ConversationPlanner(repository, compactor)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-conversation-planner-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    // the count alone is no reason to keep the person waiting on an extra model call before the reply
    @Test
    fun `a history that only outgrew the count is not recapped in front of the reply`() = runBlocking {
        repeat(45) { repository.appendInteraction(SCOPE, exchange(it)) }

        val plan = planner.planForPrompt(SCOPE, tokenBudget = 1_000_000)

        assertTrue(plan.compactablePrefix.isNotEmpty())
        assertEquals(0, compactor.calls)
        assertNull(repository.load(SCOPE).summary)
    }

    @Test
    fun `the recap the count asks for is made by the worker once the turn has ended`() = runBlocking {
        repeat(45) { repository.appendInteraction(SCOPE, exchange(it)) }
        val worker = planner.launchIn(this)

        planner.recapLater(SCOPE, tokenBudget = 1_000_000)
        withTimeout(5.seconds) { while (repository.load(SCOPE).summary == null) delay(10) }
        worker.cancelAndJoin()

        assertEquals(1, compactor.calls)
        assertEquals("recap", repository.load(SCOPE).summary)
        assertTrue(repository.load(SCOPE).interactions.size <= 40)
    }

    @Test
    fun `a history that does not fit is recapped before the reply`() = runBlocking {
        repeat(10) { repository.appendInteraction(SCOPE, exchange(it, "u".repeat(600))) }

        val plan = planner.planForPrompt(SCOPE, tokenBudget = 500)

        assertEquals(1, compactor.calls)
        assertEquals("recap", plan.prompt.summary)
    }

    private fun exchange(index: Int, text: String = "message $index"): List<ChatTurn> =
        listOf(ChatTurn(ChatRole.USER, text), ChatTurn(ChatRole.ASSISTANT, "answer $index"))

    private class RecordingCompactor : ConversationCompactor {
        var calls = 0

        override suspend fun compact(
            previousSummary: String?,
            interactions: List<ConversationInteraction>,
        ): CompactedConversation {
            calls++

            return CompactedConversation("recap", interactions.last().lastMessageId, interactions.size)
        }
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

    private companion object {
        val SCOPE = testScope(userId = 100, chatId = 100)
    }
}
