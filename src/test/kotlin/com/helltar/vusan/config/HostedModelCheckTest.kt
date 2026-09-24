package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostedModelCheckTest {

    @Test
    fun `a model the catalog knows is not asked about at all`() = runBlocking {
        var requests = 0
        val http = Http.createClient(MockEngine { requests++; respond("") })

        verifyOpenAiModel(http, "key", "gpt-5.4-mini")
        verifyAnthropicModel(http, "key", "claude-sonnet-5")
        verifyAnthropicModel(http, "key", "claude-haiku-4-5-20251001")

        assertEquals(0, requests)
    }

    @Test
    fun `a model OpenAI serves passes, and the key goes with the question`() = runBlocking {
        var authorization: String? = null
        val http =
            Http.createClient(
                MockEngine { request ->
                    authorization = request.headers[HttpHeaders.Authorization]
                    assertEquals("/v1/models/gpt-6-luna", request.url.encodedPath)
                    respond("""{"id":"gpt-6-luna","object":"model"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )

        verifyOpenAiModel(http, "sk-test", "gpt-6-luna")

        assertEquals("Bearer sk-test", authorization)
    }

    @Test
    fun `a model Anthropic serves passes, asked the way its API expects`() = runBlocking {
        var apiKey: String? = null
        var version: String? = null
        val http =
            Http.createClient(
                MockEngine { request ->
                    apiKey = request.headers["x-api-key"]
                    version = request.headers["anthropic-version"]
                    assertEquals("api.anthropic.com", request.url.host)
                    assertEquals("/v1/models/claude-opus-5-5", request.url.encodedPath)
                    respond("""{"id":"claude-opus-5-5","type":"model"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            )

        verifyAnthropicModel(http, "sk-ant-test", "claude-opus-5-5")

        assertEquals("sk-ant-test", apiKey)
        assertEquals("2023-06-01", version)
    }

    @Test
    fun `a model the provider does not know stops the startup`() = runBlocking {
        val http = Http.createClient(MockEngine { respond("""{"error":{"code":"model_not_found"}}""", status = HttpStatusCode.NotFound) })

        val openAi = assertFailsWith<IllegalStateException> { verifyOpenAiModel(http, "key", "gpt-6-lunar") }
        val anthropic = assertFailsWith<IllegalStateException> { verifyAnthropicModel(http, "key", "claude-opus-5-6") }

        assertContains(openAi.message.orEmpty(), "gpt-6-lunar")
        assertContains(anthropic.message.orEmpty(), "claude-opus-5-6")
    }

    @Test
    fun `an answer that is neither yes nor no leaves the model alone`() = runBlocking {
        val http = Http.createClient(MockEngine { respond("", status = HttpStatusCode.ServiceUnavailable) })

        verifyOpenAiModel(http, "key", "gpt-6-luna")
        verifyAnthropicModel(http, "key", "claude-opus-5-5")
    }
}
