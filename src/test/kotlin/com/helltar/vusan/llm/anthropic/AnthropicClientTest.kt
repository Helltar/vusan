package com.helltar.vusan.llm.anthropic

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.LlmProvider
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MODEL = LlmModel(LlmProvider.ANTHROPIC, "claude-opus-5-5", contextWindowTokens = 1_000_000, maxOutputTokens = 128_000)
private val DATED_MODEL = LlmModel(LlmProvider.ANTHROPIC, "claude-haiku-4-5-20251001", contextWindowTokens = 200_000, maxOutputTokens = 64_000, takesEffort = false)

private val TOOL =
    ToolDefinition("lookUp", "Looks something up.", Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject)

private const val REPLY =
    """{"id":"msg-1","type":"message","role":"assistant","model":"claude-opus-5-5",
       "content":[
         {"type":"thinking","thinking":"","signature":"sig-1"},
         {"type":"text","text":"ok"},
         {"type":"tool_use","id":"toolu-1","name":"lookUp","input":{"query":"cats"}},
         {"type":"server_tool_use","id":"srv-1","name":"web_search","input":{}}
       ],
       "stop_reason":"tool_use","stop_sequence":null,
       "usage":{"input_tokens":4,"output_tokens":52,"cache_read_input_tokens":5000,"cache_creation_input_tokens":200}}"""

class AnthropicClientTest {

    private val sent = mutableListOf<JsonObject>()
    private var sentHeaders: Headers? = null

    private fun client(reply: String = REPLY, status: HttpStatusCode = HttpStatusCode.OK): AnthropicClient {
        val engine =
            MockEngine { request ->
                sent += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                sentHeaders = request.headers
                respond(reply, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }

        return AnthropicClient(HttpClient(engine), apiKey = "key")
    }

    private val basic = arrayOf(Message.System("stable instructions"), Message.User("current request"))

    private fun request(vararg messages: Message, tools: List<ToolDefinition> = listOf(TOOL), options: RequestOptions = RequestOptions()) =
        ChatRequest(messages.toList(), tools, options)

    @Test
    fun `a chat request carries the ceiling, adaptive thinking with drop_block, the effort and two breakpoints`() = runBlocking {
        client().complete(MODEL, request(*basic, options = RequestOptions(reasoningEffort = ReasoningEffort.HIGH)))

        val body = sent.single()
        assertEquals("claude-opus-5-5", body.getValue("model").jsonPrimitive.content)
        assertEquals(128_000, body.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertEquals("adaptive", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("drop_block", body.getValue("thinking").jsonObject.getValue("block_binding").jsonObject.getValue("prefix_mismatch_behavior").jsonPrimitive.content)
        assertEquals("high", body.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("ephemeral", body.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)

        val system = body.getValue("system").jsonArray.single().jsonObject
        assertEquals("stable instructions", system.getValue("text").jsonPrimitive.content)
        assertEquals("ephemeral", system.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)

        val tool = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals(TOOL.parameters, tool.getValue("input_schema").jsonObject)

        assertEquals("key", sentHeaders?.get("x-api-key"))
        assertEquals(AnthropicClient.THINKING_BINDING_CONTROLS_BETA, sentHeaders?.get("anthropic-beta"))
        assertEquals(AnthropicClient.API_VERSION, sentHeaders?.get("anthropic-version"))
    }

    // the recap repeats nothing, so a write would buy a read nobody makes
    @Test
    fun `a request without caching marks nothing`() = runBlocking {
        client().complete(MODEL, request(*basic, options = RequestOptions(cachePrompt = false)))

        val body = sent.single()
        assertNull(body["cache_control"])
        assertNull(body.getValue("system").jsonArray.single().jsonObject["cache_control"])
    }

    // the api refuses `adaptive` and `effort` on every model it still serves under a dated snapshot id
    @Test
    fun `a model from before adaptive thinking is sent neither thinking nor effort`() = runBlocking {
        client().complete(DATED_MODEL, request(*basic))

        assertNull(sent.single()["thinking"])
        assertNull(sent.single()["output_config"])
        assertEquals(64_000, sent.single().getValue("max_tokens").jsonPrimitive.content.toInt())

        assertFailsWith<IllegalArgumentException> { client().complete(DATED_MODEL, request(*basic, options = RequestOptions(reasoningEffort = ReasoningEffort.LOW))) }
        assertFailsWith<IllegalArgumentException> { client().complete(MODEL, request(*basic, options = RequestOptions(reasoningEffort = ReasoningEffort.NONE))) }
    }

    @Test
    fun `a reply yields text, the tool call, the raw thinking block and the cache figures, and skips what it cannot read`() = runBlocking {
        val reply = client().complete(MODEL, request(*basic))

        assertEquals("ok", reply.message.text)
        assertEquals(StopReason.TOOL_CALLS, reply.stopReason)
        assertEquals("cats", reply.message.toolCalls.single().arguments.getValue("query").jsonPrimitive.content)
        assertEquals("toolu-1", reply.message.toolCalls.single().id)

        val thinking = reply.message.parts.filterIsInstance<Part.Reasoning>().single()
        assertEquals("sig-1", thinking.raw.getValue("signature").jsonPrimitive.content)
        assertEquals(3, reply.message.parts.size, "the server tool block is skipped")

        assertEquals(4, reply.usage?.inputTokens)
        assertEquals(52, reply.usage?.outputTokens)
        assertEquals(5000, reply.usage?.cacheReadTokens)
        assertEquals(200, reply.usage?.cacheWriteTokens)
    }

    // a tool loop sends the reply back: the thinking block verbatim, the call as tool_use, the results in one user message
    @Test
    fun `an assistant turn and its tool results replay in the shape the api wants`() = runBlocking {
        val client = client()
        val first = client.complete(MODEL, request(*basic)).message
        sent.clear()

        client.complete(MODEL, request(*basic, first, Message.ToolResults(listOf(ToolResult("toolu-1", "lookUp", "found", isError = true)))))

        val messages = sent.single().getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.getValue("role").jsonPrimitive.content })

        val assistant = messages[1].getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text", "tool_use"), assistant.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("sig-1", assistant[0].getValue("signature").jsonPrimitive.content)
        assertEquals("cats", assistant[2].getValue("input").jsonObject.getValue("query").jsonPrimitive.content)

        val result = messages[2].getValue("content").jsonArray.single().jsonObject
        assertEquals("tool_result", result.getValue("type").jsonPrimitive.content)
        assertEquals("toolu-1", result.getValue("tool_use_id").jsonPrimitive.content)
        assertEquals(true, result.getValue("is_error").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a blank assistant text and a foreign reasoning block are left out of the replay`() = runBlocking {
        val assistant = Message.Assistant(listOf(Part.Reasoning(LlmProvider.OPENAI, buildJsonObject { put("type", "reasoning") }), Part.Text("  "), Part.Text("real")))

        client().complete(MODEL, request(*basic, assistant, Message.User("next")))

        val content = sent.single().getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonArray
        assertEquals(1, content.size)
        assertEquals("real", content.single().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `an image travels as a base64 source`() = runBlocking {
        client().complete(MODEL, request(Message.User(listOf(Part.Text("what"), Part.Image(byteArrayOf(1, 2, 3), "image/png")))))

        val image = sent.single().getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray[1].jsonObject
        assertEquals("image", image.getValue("type").jsonPrimitive.content)
        assertEquals("image/png", image.getValue("source").jsonObject.getValue("media_type").jsonPrimitive.content)
        assertEquals("AQID", image.getValue("source").jsonObject.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun `a refusal and an output ceiling are read off the stop reason`() = runBlocking {
        val refused = client(reply = REPLY.replace(""""stop_reason":"tool_use"""", """"stop_reason":"refusal"""").replace("""{"type":"tool_use","id":"toolu-1","name":"lookUp","input":{"query":"cats"}},""", ""))
        assertEquals(StopReason.REFUSAL, refused.complete(MODEL, request(*basic)).stopReason)

        val cut = client(reply = REPLY.replace(""""stop_reason":"tool_use"""", """"stop_reason":"max_tokens"""").replace("""{"type":"tool_use","id":"toolu-1","name":"lookUp","input":{"query":"cats"}},""", ""))
        assertEquals(StopReason.MAX_TOKENS, cut.complete(MODEL, request(*basic)).stopReason)
    }

    @Test
    fun `a non-2xx answer is an error with its status and body`() = runBlocking {
        val failure = assertFailsWith<com.helltar.vusan.llm.LlmException> {
            client(reply = """{"type":"error","error":{"type":"overloaded_error"}}""", status = HttpStatusCode(529, "Overloaded")).complete(MODEL, request(*basic))
        }

        assertEquals(529, failure.status)
        assertTrue("overloaded_error" in failure.body.orEmpty())
    }
}
