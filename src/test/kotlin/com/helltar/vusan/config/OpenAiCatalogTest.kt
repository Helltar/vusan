package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenAiCatalogTest {

    @Test
    fun `a model the catalog knows is not asked about at all`() = runBlocking {
        var requests = 0
        val http = Http.createClient(MockEngine { requests++; respond("") })

        verifyOpenAiModel(http, "key", "gpt-5.4-mini")

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
    fun `a model OpenAI does not know stops the startup`() = runBlocking {
        val http = Http.createClient(MockEngine { respond("""{"error":{"code":"model_not_found"}}""", status = HttpStatusCode.NotFound) })

        val failure = assertFailsWith<IllegalStateException> { verifyOpenAiModel(http, "key", "gpt-5.6-lunar") }

        assertContains(failure.message.orEmpty(), "gpt-5.6-lunar")
    }

    @Test
    fun `an answer that is neither yes nor no leaves the model alone`() = runBlocking {
        val http = Http.createClient(MockEngine { respond("", status = HttpStatusCode.ServiceUnavailable) })

        verifyOpenAiModel(http, "key", "gpt-6-luna")
    }
}
