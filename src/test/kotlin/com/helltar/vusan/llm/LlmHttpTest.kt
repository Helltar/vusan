package com.helltar.vusan.llm

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmHttpTest {

    // the event stream spec ends a line with LF, CRLF or a bare CR, and a stream cut off mid-way still
    // hands over the line it was in the middle of
    @Test
    fun `every data payload arrives whatever ends its line`() = runBlocking {
        val body = "data: {\"n\":1}\n\ndata: {\"n\":2}\r\n\r\nevent: ping\rdata: {\"n\":3}\r\rdata: [DONE]\n\ndata: {\"n\":4}"

        assertEquals(listOf(1, 2, 3, 4), events(body).map { it.getValue("n").jsonPrimitive.content.toInt() })
    }

    @Test
    fun `a failed stream is the error body, not a stream`() = runBlocking {
        val error = assertFailsWith<LlmException> { events("""{"error":"no"}""", HttpStatusCode.TooManyRequests) }

        assertEquals(429, error.status)
        assertEquals("""{"error":"no"}""", error.body)
    }
}

private suspend fun events(body: String, status: HttpStatusCode = HttpStatusCode.OK): List<JsonObject> {
    val http = HttpClient(MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "text/event-stream")) })
    val received = mutableListOf<JsonObject>()

    http.postEventStream("test", "https://example.invalid/v1/responses", buildJsonObject { }, emptyMap()) { received += it }

    return received
}
