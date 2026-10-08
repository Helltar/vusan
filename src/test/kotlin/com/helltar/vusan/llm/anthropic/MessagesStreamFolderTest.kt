package com.helltar.vusan.llm.anthropic

import com.helltar.vusan.llm.LlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val START =
    """{"type":"message_start","message":{"id":"msg-1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[],"stop_reason":null,"stop_details":null,
       "usage":{"input_tokens":4,"cache_creation_input_tokens":200,"cache_read_input_tokens":5000,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":200},"output_tokens":1},
       "input_transformations":[]}}"""

private val EVENTS =
    listOf(
        START,
        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        """{"type":"ping"}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"th"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"ink"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"o"}}""",
        """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"k"}}""",
        """{"type":"content_block_stop","index":1}""",
        """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu-1","name":"lookUp","input":{},"caller":{"type":"direct"}}}""",
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":""}}""",
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"que"}}""",
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"ry\":\"cats\"}"}}""",
        """{"type":"content_block_stop","index":2}""",
        """{"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"toolu-2","name":"lookUp","input":{}}}""",
        """{"type":"content_block_stop","index":3}""",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null,"stop_details":null},"usage":{"input_tokens":4,"cache_creation_input_tokens":200,"cache_read_input_tokens":5000,"output_tokens":52}}""",
        """{"type":"message_stop"}""",
    )

class MessagesStreamFolderTest {

    private fun fold(events: List<String>): MessagesStreamFolder =
        MessagesStreamFolder("Anthropic").apply { events.forEach { accept(Json.parseToJsonElement(it).jsonObject) } }

    @Test
    fun `the blocks grow by their deltas and the last event's stop reason and usage replace the envelope's`() {
        val response = fold(EVENTS).response()
        val content = response.getValue("content").jsonArray.map { it.jsonObject }

        assertEquals(listOf("thinking", "text", "tool_use", "tool_use"), content.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("think", content[0].getValue("thinking").jsonPrimitive.content)
        assertEquals("sig-1", content[0].getValue("signature").jsonPrimitive.content)
        assertEquals("ok", content[1].getValue("text").jsonPrimitive.content)
        assertEquals("cats", content[2].getValue("input").jsonObject.getValue("query").jsonPrimitive.content)
        assertTrue(content[3].getValue("input").jsonObject.isEmpty(), "a call that sent no fragments keeps the empty input it opened with")

        assertEquals("tool_use", response.getValue("stop_reason").jsonPrimitive.content)

        val usage = response.getValue("usage").jsonObject
        assertEquals(52, usage.getValue("output_tokens").jsonPrimitive.content.toInt())
        assertEquals(5000, usage.getValue("cache_read_input_tokens").jsonPrimitive.content.toInt())
        assertEquals(200, usage.getValue("cache_creation").jsonObject.getValue("ephemeral_1h_input_tokens").jsonPrimitive.content.toInt())
    }

    @Test
    fun `an error event and a stream cut short are errors the retry can read`() {
        val failed = assertFailsWith<LlmException> { fold(listOf(START, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")) }
        assertEquals(200, failed.status)
        assertTrue("overloaded_error" in failed.body.orEmpty())

        val cut = assertFailsWith<LlmException> { fold(EVENTS.dropLast(1)).response() }
        assertEquals(null, cut.status)
        assertEquals("stream ended without a completed response", cut.body)
    }

    @Test
    fun `a garbled tool input folds into an empty object rather than a failed call`() {
        val events =
            listOf(
                START,
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu-1","name":"lookUp","input":{}}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\": "}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":9}}""",
                """{"type":"message_stop"}""",
            )

        val content = fold(events).response().getValue("content").jsonArray.single().jsonObject
        assertTrue(content.getValue("input").jsonObject.isEmpty())
    }
}
