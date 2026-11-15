package com.helltar.vusan.llm.typesafe

import com.helltar.vusan.llm.LlmException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SystemOneClientTest {

    private var sentTo = ""
    private var sentKey: String? = null
    private var sent = JsonObject(emptyMap())

    private val state = buildJsonObject { put("text", "hello there") }
    private val question = NoulQuestion("Is `text` a greeting?", whenTrue = "It greets someone.", whenFalse = "It says something else.")

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, reply: String) =
        SystemOneClient(
            HttpClient(
                MockEngine { request ->
                    sentTo = request.url.toString()
                    sentKey = request.headers[HttpHeaders.Authorization]
                    sent = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject

                    respond(reply, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            ),
            apiKey = "key",
        )

    @Test
    fun `a question goes out as a noul with its criteria, under the name it was asked by`() {
        val answers =
            runBlocking {
                client(reply = """{"model":"jev-1.14.0","answers":{"greeting":{"type":"noul","noul":0.93}},"usage":{"input_tokens":40,"output_tokens":4}}""")
                    .nouls("jev-latest", state, mapOf("greeting" to question))
            }

        assertEquals(mapOf("greeting" to 0.93), answers)
        assertEquals("https://api.typesafe.ai/v1/systemone", sentTo)
        assertEquals("Bearer key", sentKey)
        assertEquals("jev-latest", sent.getValue("model").jsonPrimitive.content)
        assertEquals(state, sent.getValue("state"))

        val asked = sent.getValue("questions").jsonObject.getValue("greeting").jsonObject
        assertEquals("noul", asked.getValue("type").jsonPrimitive.content)
        assertEquals("Is `text` a greeting?", asked.getValue("instructions").jsonPrimitive.content)
        assertEquals("It greets someone.", asked.getValue("criteria").jsonObject.getValue("true").jsonPrimitive.content)
        assertEquals("It says something else.", asked.getValue("criteria").jsonObject.getValue("false").jsonPrimitive.content)
    }

    @Test
    fun `a question the reply does not answer is left out`() {
        val answers =
            runBlocking {
                client(reply = """{"model":"jev-1.14.0","answers":{"other":{"type":"noul","noul":0.5}}}""")
                    .nouls("jev-latest", state, mapOf("greeting" to question))
            }

        assertEquals(emptyMap(), answers)
    }

    @Test
    fun `a refusal carries the status and the body`() {
        val refusal =
            assertFailsWith<LlmException> {
                runBlocking {
                    client(HttpStatusCode.Unauthorized, """{"detail":"Invalid API key"}""").nouls("jev-latest", state, mapOf("greeting" to question))
                }
            }

        assertEquals(401, refusal.status)
        assertEquals("""{"detail":"Invalid API key"}""", refusal.body)
    }
}
