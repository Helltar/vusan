package com.helltar.vusan.llm

import com.helltar.vusan.agent.providerOutage
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val PRIMARY_MODEL = LlmModel(LlmProvider.OPENAI, "gpt-5.6-sol", 1_050_000)
private val FALLBACK_MODEL = LlmModel(LlmProvider.ANTHROPIC, "claude-opus-5-5", 1_000_000)
private val FALLBACK_OPTIONS = RequestOptions(reasoningEffort = ReasoningEffort.HIGH)

private const val USAGE_LIMIT_BODY = """{"error":{"type":"usage_limit_reached","resets_in_seconds":7200}}"""

class FallbackLlmClientTest {

    private val start: Instant = Instant.parse("2026-09-17T10:00:00Z")

    private class TickingClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private class Scripted(private val reply: String, var failWith: Throwable? = null) : LlmClient {

        val calls = mutableListOf<Pair<ChatRequest, LlmModel>>()

        override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
            calls += request to model
            failWith?.let { throw it }

            return textReply(reply)
        }
    }

    private fun client(primary: Scripted, fallback: Scripted, clock: Clock) =
        FallbackLlmClient(primary, "subscription", fallback, "api key", FALLBACK_MODEL, FALLBACK_OPTIONS, { e, now -> e.providerOutage(now) }, clock)

    private fun request(cacheKey: String? = "vusan-abc") = ChatRequest(listOf(Message.User("hi")), options = RequestOptions(promptCacheKey = cacheKey))

    @Test
    fun `a spent subscription hands the same call to the fallback with its own model and options`() = runBlocking {
        val primary = Scripted("from primary", failWith = LlmException("Codex", 429, USAGE_LIMIT_BODY))
        val fallback = Scripted("from fallback")
        val client = client(primary, fallback, TickingClock(start))

        val reply = client.complete(PRIMARY_MODEL, request())

        assertEquals("from fallback", reply.message.text)
        assertEquals(FALLBACK_MODEL, fallback.calls.single().second)
        assertEquals(ReasoningEffort.HIGH, fallback.calls.single().first.options.reasoningEffort)
        assertNull(fallback.calls.single().first.options.promptCacheKey, "a fallback that keeps no key is not handed one")
    }

    @Test
    fun `the conversation's cache key travels to a fallback that keeps one`() = runBlocking {
        val primary = Scripted("x", failWith = LlmException("Codex", 429, USAGE_LIMIT_BODY))
        val fallback = Scripted("y")
        val client = FallbackLlmClient(primary, "a", fallback, "b", PRIMARY_MODEL, RequestOptions(promptCacheKey = "vusan"), { e, now -> e.providerOutage(now) }, TickingClock(start))

        client.complete(PRIMARY_MODEL, request("vusan-abc"))

        assertEquals("vusan-abc", fallback.calls.single().first.options.promptCacheKey)
    }

    @Test
    fun `the primary is left alone until the deadline it named, then probed once`() = runBlocking {
        val clock = TickingClock(start)
        val primary = Scripted("from primary", failWith = LlmException("Codex", 429, USAGE_LIMIT_BODY))
        val fallback = Scripted("from fallback")
        val client = client(primary, fallback, clock)

        client.complete(PRIMARY_MODEL, request())
        clock.now = start.plus(1.hours.toJavaDuration())
        client.complete(PRIMARY_MODEL, request())
        assertEquals(1, primary.calls.size, "the primary is not asked while it is out")
        assertEquals(2, fallback.calls.size)

        val inUse = assertNotNull(client.fallbackInUse)
        assertEquals(FALLBACK_MODEL.id, inUse.model)
        assertEquals(1.hours, inUse.primaryBackIn)

        primary.failWith = null
        clock.now = start.plus(2.hours.toJavaDuration()).plusSeconds(1)
        assertEquals("from primary", client.complete(PRIMARY_MODEL, request()).message.text)
        assertNull(client.fallbackInUse)
    }

    @Test
    fun `a moment's rate limit hands the call over and keeps the primary out for minutes, not hours`() = runBlocking {
        val clock = TickingClock(start)
        val primary = Scripted("from primary", failWith = LlmException("OpenAI", 429, "rate limit exceeded"))
        val fallback = Scripted("from fallback")
        val client = client(primary, fallback, clock)

        assertEquals("from fallback", client.complete(PRIMARY_MODEL, request()).message.text)
        assertNull(client.fallbackInUse?.primaryBackIn, "a deadline the provider did not name is not repeated to anybody")

        primary.failWith = null
        clock.now = start.plus(3.minutes.toJavaDuration())
        assertEquals("from primary", client.complete(PRIMARY_MODEL, request()).message.text)
    }

    @Test
    fun `a call that never got an answer counts the same way`() = runBlocking {
        val primary = Scripted("x", failWith = LlmException("OpenAI", null, "connection reset"))
        val fallback = Scripted("from fallback")

        assertEquals("from fallback", client(primary, fallback, TickingClock(start)).complete(PRIMARY_MODEL, request()).message.text)
    }

    @Test
    fun `a content refusal is not an outage and reaches the caller`() = runBlocking {
        val primary = Scripted("x", failWith = LlmException("OpenAI", 400, """{"error":{"code":"content_policy_violation"}}"""))
        val fallback = Scripted("from fallback")
        val client = client(primary, fallback, TickingClock(start))

        assertFailsWith<LlmException> { client.complete(PRIMARY_MODEL, request()) }
        assertTrue(fallback.calls.isEmpty())
        assertFalse(client.fallbackInUse != null)
    }

    @Test
    fun `a failure that is not the provider's reaches the caller too`() = runBlocking {
        val primary = Scripted("x", failWith = IllegalStateException("a bug"))
        val fallback = Scripted("from fallback")

        assertFailsWith<IllegalStateException> { client(primary, fallback, TickingClock(start)).complete(PRIMARY_MODEL, request()) }
        assertTrue(fallback.calls.isEmpty())
    }
}
