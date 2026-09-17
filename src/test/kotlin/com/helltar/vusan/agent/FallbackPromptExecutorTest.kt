package com.helltar.vusan.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val PRIMARY_MODEL = LLModel(LLMProvider.OpenAI, "gpt-5.6-sol", emptyList())
private val FALLBACK_MODEL = LLModel(LLMProvider.OpenAI, "gpt-5.4-mini", emptyList())
private val FALLBACK_PARAMS = OpenAIResponsesParams(promptCacheKey = "vusan", parallelToolCalls = false)

private const val USAGE_LIMIT_BODY =
    "Status code: 429 Error body: {\"error\":{\"type\":\"usage_limit_reached\",\"resets_in_seconds\":7200}}"

class FallbackPromptExecutorTest {

    private val start: Instant = Instant.parse("2026-09-17T10:00:00Z")

    private class TickingClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private class ScriptedExecutor(private val reply: String, var failWith: Throwable? = null) : PromptExecutor() {

        val calls = mutableListOf<Pair<Prompt, LLModel>>()

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            calls += prompt to model
            failWith?.let { throw it }

            return Message.Assistant(content = reply, metaInfo = ResponseMetaInfo.Empty)
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            error("executeStreaming not used in test")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("moderate not used in test")

        override fun close() = Unit
    }

    private fun executor(primary: ScriptedExecutor, fallback: ScriptedExecutor, clock: Clock) =
        FallbackPromptExecutor(primary, "subscription", fallback, "api key", FALLBACK_MODEL, FALLBACK_PARAMS, clock)

    private fun prompt() =
        Prompt.build("test", params = OpenAIResponsesParams(promptCacheKey = "vusan-abc", parallelToolCalls = false)) { }

    @Test
    fun `a spent subscription hands the same call to the fallback with its own model and params`() = runBlocking {
        val primary = ScriptedExecutor("from primary", failWith = LLMClientException("OpenAILLMClient", USAGE_LIMIT_BODY))
        val fallback = ScriptedExecutor("from fallback")
        val executor = executor(primary, fallback, TickingClock(start))

        assertEquals("from fallback", executor.execute(prompt(), PRIMARY_MODEL).textContent())

        val (sentPrompt, sentModel) = fallback.calls.single()
        assertEquals(FALLBACK_MODEL, sentModel)

        // the fallback's own params, except the cache key, which belongs to the conversation
        val params = sentPrompt.params as OpenAIResponsesParams
        assertEquals("vusan-abc", params.promptCacheKey)
        assertEquals(false, params.parallelToolCalls)
        assertEquals(FALLBACK_MODEL.id, executor.fallbackModelInUse, "the turn cannot say which model answered")
    }

    @Test
    fun `the primary is left alone until the deadline it named, then probed once`() = runBlocking {
        val clock = TickingClock(start)
        val primary = ScriptedExecutor("from primary", failWith = LLMClientException("OpenAILLMClient", USAGE_LIMIT_BODY))
        val fallback = ScriptedExecutor("from fallback")
        val executor = executor(primary, fallback, clock)

        executor.execute(prompt(), PRIMARY_MODEL)
        executor.execute(prompt(), PRIMARY_MODEL)
        assertEquals(1, primary.calls.size, "the primary was asked again during its outage")
        assertEquals(2, fallback.calls.size)

        // two hours later the limit has lifted
        clock.now = start.plus(2.hours.toJavaDuration()).plusSeconds(1)
        primary.failWith = null

        assertEquals("from primary", executor.execute(prompt(), PRIMARY_MODEL).textContent())
        assertNull(executor.fallbackModelInUse)
        assertEquals(2, fallback.calls.size, "the fallback was asked after the primary came back")
    }

    @Test
    fun `a probe that fails again extends the outage`() = runBlocking {
        val clock = TickingClock(start)
        val primary = ScriptedExecutor("from primary", failWith = LLMClientException("OpenAILLMClient", USAGE_LIMIT_BODY))
        val fallback = ScriptedExecutor("from fallback")
        val executor = executor(primary, fallback, clock)

        executor.execute(prompt(), PRIMARY_MODEL)
        clock.now = start.plus(3.hours.toJavaDuration())
        executor.execute(prompt(), PRIMARY_MODEL)

        assertEquals(2, primary.calls.size)
        assertNotNull(executor.fallbackModelInUse)
    }

    @Test
    fun `a moment's rate limit hands the call over and keeps the primary out for minutes, not hours`() = runBlocking {
        val clock = TickingClock(start)
        val primary = ScriptedExecutor("from primary", failWith = LLMClientException("OpenAILLMClient", "Status code: 429 rate limit"))
        val fallback = ScriptedExecutor("from fallback")
        val executor = executor(primary, fallback, clock)

        assertEquals("from fallback", executor.execute(prompt(), PRIMARY_MODEL).textContent())
        assertNotNull(executor.fallbackModelInUse)

        clock.now = start.plus(3.minutes.toJavaDuration())
        assertNull(executor.fallbackModelInUse)
    }

    @Test
    fun `a dropped connection counts the same way`() = runBlocking {
        val primary = ScriptedExecutor("from primary", failWith = RuntimeException("wrapped", java.net.ConnectException("refused")))
        val fallback = ScriptedExecutor("from fallback")
        val executor = executor(primary, fallback, TickingClock(start))

        assertEquals("from fallback", executor.execute(prompt(), PRIMARY_MODEL).textContent())
        assertNotNull(executor.fallbackModelInUse)
    }

    @Test
    fun `a content refusal is not an outage and reaches the caller`() = runBlocking {
        val body = "Status code: 400 Error body: {\"error\":{\"code\":\"invalid_prompt\",\"message\":\"flagged by content_policy\"}}"
        val primary = ScriptedExecutor("from primary", failWith = LLMClientException("OpenAILLMClient", body))
        val fallback = ScriptedExecutor("from fallback")
        val executor = executor(primary, fallback, TickingClock(start))

        assertFailsWith<LLMClientException> { executor.execute(prompt(), PRIMARY_MODEL) }
        assertTrue(fallback.calls.isEmpty())
        assertNull(executor.fallbackModelInUse)
    }

    @Test
    fun `an expired sign-in counts as an outage without a deadline`() = runBlocking {
        val clock = TickingClock(start)
        val primary = ScriptedExecutor("from primary", failWith = LLMClientException("OpenAILLMClient", "Status code: 401\ntoken_expired"))
        val fallback = ScriptedExecutor("from fallback")
        val executor = executor(primary, fallback, clock)

        executor.execute(prompt(), PRIMARY_MODEL)
        assertNotNull(executor.fallbackModelInUse)

        clock.now = start.plus(31.minutes.toJavaDuration())
        assertNull(executor.fallbackModelInUse)
    }
}
