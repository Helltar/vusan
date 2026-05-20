package com.helltar.vusan.infra

import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HttpTest {

    @Test
    fun `createClient reports non-success responses without response body or query`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """{"error":"raw provider payload"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )

        val error =
            http.use { http ->
                assertFailsWith<IllegalStateException> {
                    http.get("https://api.example.test/search?api_key=secret")
                }
            }

        assertEquals("HTTP 400 from api.example.test", error.message)
        assertFalse(error.message.orEmpty().contains("raw provider payload"))
        assertFalse(error.message.orEmpty().contains("api_key"))
    }
}
