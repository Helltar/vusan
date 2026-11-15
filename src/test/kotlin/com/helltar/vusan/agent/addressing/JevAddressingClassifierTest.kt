package com.helltar.vusan.agent.addressing

import com.helltar.vusan.llm.typesafe.SystemOneClient
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JevAddressingClassifierTest {

    private var sent = JsonObject(emptyMap())

    private val input =
        AddressingInput(
            botNames = listOf("Robin", "robbie"),
            recent = listOf(ChatLine("Dana", "anyone seen the report?")),
            message = ChatLine("Sam", "robbie, what do you make of it?"),
        )

    private fun verdict(answers: String): Boolean? {
        val http =
            HttpClient(
                MockEngine { request ->
                    sent = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    respond("""{"model":"jev-1.14.0","answers":$answers}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )

        return runBlocking { JevAddressingClassifier(SystemOneClient(http, apiKey = "key"), "jev-1.14.0").isAddressed(input) }
    }

    @Test
    fun `a probability at the cut-off or above is a yes, anything under it a no`() {
        assertEquals(true, verdict("""{"addressed":{"type":"noul","noul":0.93}}"""))
        assertEquals(true, verdict("""{"addressed":{"type":"noul","noul":0.7}}"""))
        assertEquals(false, verdict("""{"addressed":{"type":"noul","noul":0.69}}"""))
        assertEquals(false, verdict("""{"addressed":{"type":"noul","noul":0.04}}"""))
    }

    @Test
    fun `an answer without the probability is no verdict`() {
        assertNull(verdict("""{"addressed":{"type":"choice","choice":"yes"}}"""))
        assertNull(verdict("{}"))
    }

    @Test
    fun `it is asked the measured wording about the state a chat model reads`() {
        verdict("""{"addressed":{"type":"noul","noul":0.9}}""")

        val question = sent.getValue("questions").jsonObject.getValue("addressed").jsonObject

        assertEquals("jev-1.14.0", sent.getValue("model").jsonPrimitive.content)
        assertEquals(addressingState(input), sent.getValue("state"))
        assertEquals(addressingQuestion(input.botNames), question.getValue("instructions").jsonPrimitive.content)
        assertEquals(ADDRESSED_WHEN, question.getValue("criteria").jsonObject.getValue("true").jsonPrimitive.content)
        assertEquals(NOT_ADDRESSED_WHEN, question.getValue("criteria").jsonObject.getValue("false").jsonPrimitive.content)
    }
}
