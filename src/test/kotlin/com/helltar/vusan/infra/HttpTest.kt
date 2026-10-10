package com.helltar.vusan.infra

import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HttpTest {

    @Test
    fun `createClient reports non-success responses with body preview and without query`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """{"error":"raw provider payload"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            )

        val error =
            http.use { http ->
                assertFailsWith<IllegalStateException> {
                    http.get("https://api.example.test/search?api_key=secret")
                }
            }

        assertEquals("""HTTP 400 from api.example.test: {"error":"raw provider payload"}""", error.message)
        assertFalse(error.message.orEmpty().contains("api_key"))
    }

    // ktor names the whole URL in a timeout, and a tool's guard hands the message to the model
    @Test
    fun `a timeout names the host and never the query`() = runBlocking {
        val http = Http.createClient(MockEngine { throw HttpRequestTimeoutException("https://api.example.test/search?api_key=secret", 20_000L) })

        val error = http.use { http -> assertFailsWith<HttpTimedOutException> { http.get("https://api.example.test/search?api_key=secret") } }

        assertEquals("request to api.example.test timed out", error.message)
    }
}
