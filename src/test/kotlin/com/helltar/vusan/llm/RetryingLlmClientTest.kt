package com.helltar.vusan.llm

import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RetryingLlmClientTest {

    private fun error(status: Int?, body: String, cause: Throwable? = null) = LlmException("OpenAI", status, body, cause)

    @Test
    fun `a passing server failure is worth repeating`() {
        assertTrue(isRepeatableFailure(error(503, "upstream unavailable")))
        assertTrue(isRepeatableFailure(error(500, "")))
        assertTrue(isRepeatableFailure(error(200, """{"type":"response.failed","error":{"code":"server_is_overloaded"}}""")))
        assertTrue(isRepeatableFailure(LlmException("test", status = null, body = "stream ended early", cutShort = true)))
        assertTrue(isRepeatableFailure(error(null, "reset", IOException("Connection reset"))))
    }

    @Test
    fun `a spent allowance, a refusal, a bad request, a rate limit and a timeout are final`() {
        assertFalse(isRepeatableFailure(error(429, """{"error":{"type":"usage_limit_reached","resets_in_seconds":900}}""")))
        assertFalse(isRepeatableFailure(error(429, "rate limit")))
        assertFalse(isRepeatableFailure(error(500, """{"error":{"code":"cyber_policy"}}""")))
        assertFalse(isRepeatableFailure(error(400, "unknown parameter")))
        assertFalse(isRepeatableFailure(error(401, "token_expired")))
        assertFalse(isRepeatableFailure(error(null, "timed out", SocketTimeoutException("timed out"))))
        assertFalse(isRepeatableFailure(error(null, "request timeout", HttpRequestTimeoutException("https://api.openai.com/v1/responses", 300_000))))
        assertFalse(isRepeatableFailure(IllegalStateException("not a provider failure")))
    }

    @Test
    fun `a call that fails once on the server's side is made again`() = runBlocking {
        var calls = 0

        val client =
            RetryingLlmClient(
                object : LlmClient {
                    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
                        if (++calls == 1) throw error(503, "upstream unavailable")
                        return textReply("ok")
                    }
                },
                initialDelay = 1.milliseconds,
            )

        assertEquals("ok", client.complete(TEST_MODEL, ChatRequest(listOf(Message.User("hi")))).message.text)
        assertEquals(2, calls)
    }

    @Test
    fun `a spent allowance is reported at once, and a failure that lasts is reported after the last attempt`() = runBlocking {
        var calls = 0
        val spent = RetryingLlmClient(object : LlmClient { override suspend fun complete(model: LlmModel, request: ChatRequest): Reply { calls++; throw error(429, """{"error":{"type":"usage_limit_reached"}}""") } }, initialDelay = 1.milliseconds)

        assertFailsWith<LlmException> { spent.complete(TEST_MODEL, ChatRequest(listOf(Message.User("hi")))) }
        assertEquals(1, calls)

        calls = 0
        val down = RetryingLlmClient(object : LlmClient { override suspend fun complete(model: LlmModel, request: ChatRequest): Reply { calls++; throw error(503, "down") } }, maxAttempts = 3, initialDelay = 1.milliseconds)

        assertFailsWith<LlmException> { down.complete(TEST_MODEL, ChatRequest(listOf(Message.User("hi")))) }
        assertEquals(3, calls)
    }
}
