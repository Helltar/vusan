package com.helltar.vusan.budget

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.config.TokenBudgetConfig
import com.helltar.vusan.infra.Db
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

private const val ALICE = 1L
private const val BOB = 2L

class BudgetedPromptExecutorTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-budget-executor-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `without a budget the executor is handed back unwrapped`() {
        val delegate = CountingPromptExecutor()

        assertSame(delegate, TokenBudget(TokenBudgetConfig()).meter(delegate))
    }

    @Test
    fun `calls stop once the day's tokens are spent`() = runBlocking {
        val delegate = CountingPromptExecutor(inputTokens = 400, outputTokens = 100)
        val executor = TokenBudget(TokenBudgetConfig(dailyTokens = 1_000)).meter(delegate)

        executor.execute(Prompt.build("test") { }, MODEL)
        executor.execute(Prompt.build("test") { }, MODEL)

        assertFailsWith<TokenBudgetExhaustedException> { executor.execute(Prompt.build("test") { }, MODEL) }
        assertEquals(2, delegate.callCount, "the third call never reached the provider")
    }

    // the turn's author rides in the coroutine context, which is the only thing a nested call — a history
    // recap, a vision tool — carries about whose tokens it is spending.
    @Test
    fun `the call is charged to the coroutine's budget owner`() = runBlocking {
        val budget = TokenBudget(TokenBudgetConfig(dailyTokens = 1_000))
        val executor = budget.meter(CountingPromptExecutor(inputTokens = 300, outputTokens = 0))

        withContext(BudgetOwner(ALICE)) {
            executor.execute(Prompt.build("test") { }, MODEL)
            executor.execute(Prompt.build("test") { }, MODEL)
        }

        withContext(BudgetOwner(BOB)) { executor.execute(Prompt.build("test") { }, MODEL) }

        // 900 of 1000 is past the 70% mark; Alice's two calls put her over her 500 of two shares, Bob's one
        // call did not — which only holds if each call landed on the owner its coroutine carried.
        assertIs<TokenBudgetStop.UserShare>(budget.stopFor(ALICE))
        assertNull(budget.stopFor(BOB))
    }

    @Test
    fun `a call with no owner is charged to the day alone`() = runBlocking {
        val budget = TokenBudget(TokenBudgetConfig(dailyTokens = 1_000))
        val executor = budget.meter(CountingPromptExecutor(inputTokens = 700, outputTokens = 0))

        executor.execute(Prompt.build("test") { }, MODEL)

        assertNull(budget.stopFor(ALICE))
    }

    // both overloads delegate outward, so a koog agent that resolves its model first is charged the same
    // tokens once, not twice.
    @Test
    fun `a resolved model is charged once`() = runBlocking {
        val delegate = CountingPromptExecutor(inputTokens = 600, outputTokens = 0)
        val executor = TokenBudget(TokenBudgetConfig(dailyTokens = 1_000)).meter(delegate)

        val resolved = executor.resolveModel(MODEL, PromptExecutorOperation.Execute)

        // a double-charged first call would already be over the 1000-token budget by the second one.
        executor.execute(Prompt.build("test") { }, resolved)
        executor.execute(Prompt.build("test") { }, MODEL)

        assertFailsWith<TokenBudgetExhaustedException> { executor.execute(Prompt.build("test") { }, MODEL) }
        assertEquals(2, delegate.callCount, "each call was charged its 600 tokens once")
    }

    private class CountingPromptExecutor(
        private val inputTokens: Int = 0,
        private val outputTokens: Int = 0
    ) : PromptExecutor() {

        var callCount = 0
            private set

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            callCount++

            return Message.Assistant(
                content = "reply",
                metaInfo =
                    ResponseMetaInfo.Empty.copy(
                        inputTokensCount = inputTokens,
                        outputTokensCount = outputTokens
                    )
            )
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            error("executeStreaming not used in test")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("moderate not used in test")

        override fun close() = Unit
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            allowedIds = emptySet(),
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
            personality = null,
            sandboxTimeoutSeconds = 30L,
            sandboxUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )

    private companion object {
        val MODEL = LLModel(provider = LLMProvider.OpenAI, id = "test", capabilities = emptyList())
    }
}
