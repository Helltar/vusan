package com.helltar.vusan.config

import ai.koog.http.client.KoogHttpClientException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexStreamBridgeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the request is forced to stream without storing`() {
        val body = forceStreamingRequest("""{"model":"m","stream":false,"store":true}""", json)
        val root = json.parseToJsonElement(body).jsonObject

        // the backend rejects anything else: "Stream must be set to true" / "Store must be set to false"
        assertEquals(true, root["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, root["store"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("m", root["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `forcing streaming leaves a non-object body alone`() {
        assertEquals("not json", forceStreamingRequest("not json", json))
    }

    @Test
    fun `completed output is rebuilt from the item events`() {
        val response =
            collectStreamedResponse(
                listOf(
                    """data: {"type":"response.created","response":{"id":"r"}}""",
                    """data: {"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1"}}""",
                    """data: {"type":"response.output_item.done","item":{"type":"function_call","name":"sendMessage"}}""",
                    """data: {"type":"response.completed","response":{"id":"r","status":"completed","output":[]}}""",
                    "data: [DONE]"
                ),
                json,
                "codex"
            )

        // the codex backend ships an empty output on the completed event, so the items are spliced back in
        val output = checkNotNull(response["output"]).jsonArray

        assertEquals(2, output.size)
        assertEquals("reasoning", output[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("function_call", output[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("completed", response["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non-data lines and unparseable payloads are skipped`() {
        val response =
            collectStreamedResponse(
                listOf(
                    "event: response.created",
                    "data: {oops",
                    "",
                    """data: {"type":"response.completed","response":{"id":"r"}}"""
                ),
                json,
                "codex"
            )

        assertEquals("r", response["id"]?.jsonPrimitive?.content)
        assertEquals(JsonArray(emptyList()), response["output"])
    }

    @Test
    fun `a stream that never completes is an error rather than an empty reply`() {
        val error =
            assertFailsWith<KoogHttpClientException> {
                collectStreamedResponse(listOf("""data: {"type":"response.created"}"""), json, "codex")
            }

        assertTrue("completed" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a failed stream surfaces the error payload`() {
        assertFailsWith<KoogHttpClientException> {
            collectStreamedResponse(
                listOf("""data: {"type":"response.failed","response":{"error":{"message":"usage_limit_reached"}}}"""),
                json,
                "codex"
            )
        }.also { assertTrue("usage_limit_reached" in it.message.orEmpty(), it.message.orEmpty()) }
    }

    @Test
    fun `usage and model survive so the token budget still meters the turn`() {
        val response =
            collectStreamedResponse(
                listOf(
                    """data: {"type":"response.completed","response":{"id":"r","model":"gpt-5.6-terra",""" +
                            """"usage":{"input_tokens":17,"output_tokens":5,"total_tokens":22}}}"""
                ),
                json,
                "codex"
            )

        assertEquals("gpt-5.6-terra", response["model"]?.jsonPrimitive?.content)
        assertEquals(22, response["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.content?.toInt())
    }
}
