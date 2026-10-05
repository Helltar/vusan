package com.helltar.vusan.config

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LlmRetryTest {

    @Test
    fun `a passing server failure is worth repeating`() {
        assertTrue(isRepeatableFailure("Error from client: codex\nStatus code: 503\nError body: upstream unavailable"))
        assertTrue(isRepeatableFailure("Status code: 500"))
        assertTrue(isRepeatableFailure("""Status code: 200 {"type":"response.failed","error":{"code":"server_is_overloaded"}}"""))
        assertTrue(isRepeatableFailure("Codex stream ended without a completed response"))
    }

    @Test
    fun `a spent allowance, a refusal and a bad request are final`() {
        assertFalse(isRepeatableFailure("""Status code: 429 {"error":{"type":"usage_limit_reached","resets_in_seconds":900}}"""))
        assertFalse(isRepeatableFailure("Status code: 429 rate limit"))
        assertFalse(isRepeatableFailure("""Status code: 500 {"error":{"code":"cyber_policy"}}"""))
        assertFalse(isRepeatableFailure("Status code: 400 unknown parameter"))
        assertFalse(isRepeatableFailure("Status code: 401 token_expired"))
    }

    // through koog's own client, so the message the pattern reads is the one koog really builds.
    @Test
    fun `a call that fails once on the server's side is made again`() = runBlocking {
        var calls = 0

        val client =
            client { request ->
                if (++calls == 1) respond("upstream unavailable", HttpStatusCode.ServiceUnavailable)
                else respondJson(openAiReplyTo(request.body.toByteArray().decodeToString(), responses = false))
            }

        client.execute(prompt(), runtime.model, emptyList())

        assertEquals(2, calls)
    }

    @Test
    fun `a spent allowance is reported at once`() = runBlocking {
        var calls = 0

        val client =
            client {
                calls++
                respondJson("""{"error":{"type":"usage_limit_reached"}}""", HttpStatusCode.TooManyRequests)
            }

        assertFails { client.execute(prompt(), runtime.model, emptyList()) }
        assertEquals(1, calls)
    }

    private val runtime =
        resolveLlmRuntime(
            LlmProviderConfig.OpenAiCompatible(
                baseUrl = "https://example.test",
                apiKey = "key",
                model = "text-model",
                endpoint = OpenAiEndpoint.COMPLETIONS,
                requestTimeout = 120.seconds,
            ),
        )

    private fun prompt(): Prompt =
        Prompt.build("retry", params = runtime.chatParams) {
            system("stable instructions")
            user("current request")
        }

    private fun client(handler: MockRequestHandler) =
        OpenAILLMClient(
            apiKey = "key",
            settings = OpenAIClientSettings(baseUrl = "https://example.test"),
            httpClientFactory = LenientDecodingHttpClientFactory(KtorKoogHttpClient.Factory(HttpClient(MockEngine(handler)))),
        ).repeatingTransientFailures()

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
}
