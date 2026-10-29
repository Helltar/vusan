package com.helltar.vusan.llm.google

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmException
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.StopReason
import com.helltar.vusan.llm.ToolDefinition
import com.helltar.vusan.llm.ToolResult
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

private val MODEL = LlmModel("gemini-3.5-flash-lite", contextWindowTokens = 1_048_576, maxOutputTokens = 65_536)

private val TOOL =
    ToolDefinition("lookUp", "Looks something up.", Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject)

// the shape the interactions api answered with on 2026-10-29, the signature shortened
private const val CALL_REPLY =
    """{"id":"int-1","object":"interaction","model":"gemini-3.5-flash-lite","status":"requires_action",
       "steps":[
         {"type":"thought","signature":"EmAKXgFp"},
         {"type":"function_call","id":"call_37979","name":"lookUp","arguments":{"query":"cats"}}
       ],
       "usage":{"total_input_tokens":79,"total_cached_tokens":40,"total_output_tokens":24,"total_thought_tokens":6,"total_tokens":109}}"""

private const val TEXT_REPLY =
    """{"id":"int-2","object":"interaction","model":"gemini-3.5-flash-lite","status":"completed",
       "steps":[
         {"type":"thought","signature":"EmAKXgFq"},
         {"type":"model_output","content":[{"type":"text","text":"found them"},{"type":"audio","data":"AAAA"}]},
         {"type":"google_search_call","id":"s-1"}
       ],
       "usage":{"total_input_tokens":111,"total_output_tokens":10}}"""

class GoogleClientTest {

    private val sent = mutableListOf<JsonObject>()
    private var sentHeaders: Headers? = null

    private val basic = arrayOf(Message.System("stable instructions"), Message.User("current request"))

    private fun client(reply: String = CALL_REPLY, status: HttpStatusCode = HttpStatusCode.OK): GoogleClient {
        val engine =
            MockEngine { request ->
                sent += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                sentHeaders = request.headers
                respond(reply, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }

        return GoogleClient(HttpClient(engine), apiKey = "key")
    }

    private fun request(vararg messages: Message, options: RequestOptions = RequestOptions()) = ChatRequest(messages.toList(), listOf(TOOL), options)

    @Test
    fun `a request goes out stateless, the system lifted out and the tools declared`() = runBlocking {
        client().complete(MODEL, request(Message.System("first"), Message.System("second"), Message.User("current request")))

        val body = sent.single()
        assertEquals("key", sentHeaders?.get("x-goog-api-key"))
        assertEquals("gemini-3.5-flash-lite", body.getValue("model").jsonPrimitive.content)
        assertEquals("false", body.getValue("store").jsonPrimitive.content)
        assertEquals("first\n\nsecond", body.getValue("system_instruction").jsonPrimitive.content)

        val input = body.getValue("input").jsonArray.single().jsonObject
        assertEquals("user_input", input.getValue("type").jsonPrimitive.content)
        assertEquals("current request", input.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)

        val tool = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals(listOf("function", "lookUp"), listOf(tool.getValue("type").jsonPrimitive.content, tool.getValue("name").jsonPrimitive.content))
        assertEquals(TOOL.parameters, tool.getValue("parameters"))
        assertNull(body["generation_config"])
    }

    // a gemini 3 model cannot stop thinking, and `minimal` is its least
    @Test
    fun `the effort, the ceiling and a forbidden call travel in the generation config`() = runBlocking {
        val options = RequestOptions(reasoningEffort = ReasoningEffort.NONE, maxOutputTokens = 40)

        client().complete(MODEL, ChatRequest(basic.toList(), listOf(TOOL), options, mayCallTools = false))

        val config = sent.single().getValue("generation_config").jsonObject
        assertEquals("minimal", config.getValue("thinking_level").jsonPrimitive.content)
        assertEquals(40, config.getValue("max_output_tokens").jsonPrimitive.content.toInt())
        assertEquals("none", config.getValue("tool_choice").jsonPrimitive.content)

        client().complete(MODEL, request(*basic, options = RequestOptions(reasoningEffort = ReasoningEffort.HIGH)))
        assertEquals("high", sent.last().getValue("generation_config").jsonObject.getValue("thinking_level").jsonPrimitive.content)

        assertFailsWith<IllegalArgumentException> { client().complete(MODEL, request(*basic, options = RequestOptions(reasoningEffort = ReasoningEffort.XHIGH))) }
        Unit
    }

    @Test
    fun `a reply yields its thought, its calls, the stop reason and the usage`() = runBlocking {
        val reply = client().complete(MODEL, request(*basic))

        assertEquals(StopReason.TOOL_CALLS, reply.stopReason)
        assertIs<Part.Reasoning>(reply.message.parts.first())
        assertEquals("cats", reply.message.toolCalls.single().arguments.getValue("query").jsonPrimitive.content)
        assertEquals("call_37979", reply.message.toolCalls.single().id)
        assertEquals(79, reply.usage?.inputTokens)
        assertEquals(30, reply.usage?.outputTokens)
        assertEquals(40, reply.usage?.cacheReadTokens)
        assertEquals("gemini-3.5-flash-lite", reply.model)

        val text = client(reply = TEXT_REPLY).complete(MODEL, request(*basic))
        assertEquals(StopReason.END, text.stopReason)
        assertEquals("found them", text.message.text)
        assertEquals(10, text.usage?.outputTokens)
    }

    // without the thought the call came with, google answers the turn's next request with a 400
    @Test
    fun `the turn replays its thought and call as they came, with the results after them`() = runBlocking {
        val client = client()
        val first = client.complete(MODEL, request(*basic)).message
        sent.clear()

        client.complete(MODEL, request(*basic, first, Message.ToolResults(listOf(ToolResult("call_37979", "lookUp", "found", isError = true)))))

        val input = sent.single().getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user_input", "thought", "function_call", "function_result"), input.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("EmAKXgFp", input[1].getValue("signature").jsonPrimitive.content)
        assertEquals("cats", input[2].getValue("arguments").jsonObject.getValue("query").jsonPrimitive.content)
        assertEquals("call_37979", input[3].getValue("call_id").jsonPrimitive.content)
        assertEquals("found", input[3].getValue("result").jsonPrimitive.content)
        assertEquals("true", input[3].getValue("is_error").jsonPrimitive.content)
    }

    @Test
    fun `reasoning another endpoint wrote stays out, and texts between steps keep their order`() = runBlocking {
        val foreign = Part.Reasoning("https://api.anthropic.com/v1/messages", buildJsonObject { put("type", "thinking") })
        val turn =
            Message.Assistant(
                listOf(foreign, Part.Text("on it"), Part.Text(" "), Part.ToolCall("call-1", "lookUp", buildJsonObject { put("query", "cats") }), Part.Text("done")),
            )

        client().complete(MODEL, request(*basic, turn, Message.ToolResults(listOf(ToolResult("call-1", "lookUp", "found"))), Message.User("next")))

        val input = sent.single().getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("user_input", "model_output", "function_call", "model_output", "function_result", "user_input"),
            input.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals("on it", input[1].getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse(input.any { it.getValue("type").jsonPrimitive.content == "thinking" })
    }

    @Test
    fun `an image goes out as base64 beside the text`() = runBlocking {
        val user = Message.User(listOf(Part.Text("what is this"), Part.Image(byteArrayOf(1, 2, 3), "image/png")))

        client().complete(MODEL, request(Message.System("s"), user))

        val image = sent.single().getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray[1].jsonObject
        assertEquals("image", image.getValue("type").jsonPrimitive.content)
        assertEquals("image/png", image.getValue("mime_type").jsonPrimitive.content)
        assertEquals("AQID", image.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun `an answer cut short says so, and a failed one or an error status is an error`() = runBlocking {
        val cut = client(reply = TEXT_REPLY.replace("\"completed\"", "\"incomplete\"")).complete(MODEL, request(*basic))
        assertEquals(StopReason.MAX_TOKENS, cut.stopReason)

        val failed = """{"id":"int-3","status":"failed","errors":[{"code":"internal","message":"something broke"}]}"""
        assertContains(assertFailsWith<LlmException> { client(reply = failed).complete(MODEL, request(*basic)) }.body.orEmpty(), "something broke")

        val refused = """{"error":{"message":"Thinking level is not supported for this model.","code":"invalid_request"}}"""
        val failure = assertFailsWith<LlmException> { client(reply = refused, status = HttpStatusCode.BadRequest).complete(MODEL, request(*basic)) }
        assertEquals(400, failure.status)
        assertContains(failure.body.orEmpty(), "Thinking level is not supported")
    }
}
