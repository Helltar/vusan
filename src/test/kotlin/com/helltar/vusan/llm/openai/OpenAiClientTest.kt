package com.helltar.vusan.llm.openai

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmException
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MODEL = LlmModel(LlmProvider.OPENAI, "gpt-5.6-sol", contextWindowTokens = 1_050_000)
private val OLD_MODEL = LlmModel(LlmProvider.OPENAI, "gpt-5.4-mini", contextWindowTokens = 400_000)

private val TOOL =
    ToolDefinition(
        name = "lookUp",
        description = "Looks something up.",
        parameters = Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject,
    )

private const val RESPONSES_REPLY =
    """{"id":"resp-1","object":"response","created_at":0,"model":"gpt-5.6-sol","status":"completed",
       "output":[
         {"type":"reasoning","id":"rs-1","summary":[],"encrypted_content":"opaque"},
         {"type":"message","id":"msg-1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"ok","annotations":[]}]},
         {"type":"function_call","id":"fc-1","call_id":"call-1","name":"lookUp","arguments":"{\"query\":\"cats\"}","status":"completed"}
       ],
       "usage":{"input_tokens":120,"input_tokens_details":{"cached_tokens":100},"output_tokens":30}}"""

private const val COMPLETION_REPLY =
    """{"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"deepseek-chat",
       "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"reasoning_content":"thinking...",
         "tool_calls":[{"id":"call-9","type":"function","function":{"name":"lookUp","arguments":"{\"query\":\"dogs\"}"}}]}}],
       "usage":{"prompt_tokens":50,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":40}}}"""

class OpenAiClientTest {

    private val sent = mutableListOf<JsonObject>()

    private fun client(
        reply: String = RESPONSES_REPLY,
        endpoint: OpenAiEndpoint = OpenAiEndpoint.RESPONSES,
        status: HttpStatusCode = HttpStatusCode.OK,
        statelessReasoning: Boolean = true,
        explicitPromptCaching: Boolean = true,
        streamed: Boolean = false,
    ): OpenAiClient {
        val engine =
            MockEngine { request ->
                sent += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                respond(reply, status, headersOf(HttpHeaders.ContentType, if (streamed) "text/event-stream" else ContentType.Application.Json.toString()))
            }

        return OpenAiClient(
            http = HttpClient(engine),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "key",
            endpoint = endpoint,
            streamed = streamed,
            statelessReasoning = statelessReasoning,
            explicitPromptCaching = explicitPromptCaching,
        )
    }

    private fun request(vararg messages: Message, tools: List<ToolDefinition> = listOf(TOOL), options: RequestOptions = RequestOptions()) =
        ChatRequest(messages.toList(), tools, options)

    private val basic = arrayOf(Message.System("stable instructions"), Message.User("current request"))

    @Test
    fun `a responses request carries the model, the items, the tools and the options`() = runBlocking {
        client().complete(
            MODEL,
            request(*basic, options = RequestOptions(reasoningEffort = ReasoningEffort.XHIGH, verbosity = "low", promptCacheKey = "vusan-1", parallelToolCalls = false, serviceTier = "priority")),
        )

        val body = sent.single()
        assertEquals("gpt-5.6-sol", body.getValue("model").jsonPrimitive.content)
        assertEquals("xhigh", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("low", body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
        assertEquals("vusan-1", body.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals("priority", body.getValue("service_tier").jsonPrimitive.content)
        assertEquals(false, body.getValue("parallel_tool_calls").jsonPrimitive.content.toBoolean())
        assertEquals(false, body.getValue("store").jsonPrimitive.content.toBoolean())
        assertEquals(listOf("reasoning.encrypted_content"), body.getValue("include").jsonArray.map { it.jsonPrimitive.content })
        assertNull(body["stream"])

        val input = body.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals("developer", input[0].getValue("role").jsonPrimitive.content)
        assertEquals("user", input[1].getValue("role").jsonPrimitive.content)
        assertEquals("current request", input[1].getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)

        val tool = body.getValue("tools").jsonArray.single().jsonObject
        assertEquals("function", tool.getValue("type").jsonPrimitive.content)
        assertEquals("lookUp", tool.getValue("name").jsonPrimitive.content)
        assertEquals(TOOL.parameters, tool.getValue("parameters").jsonObject)
    }

    @Test
    fun `a responses reply yields text, tool calls, raw reasoning and the cache figures`() = runBlocking {
        val reply = client().complete(MODEL, request(*basic))

        assertEquals("ok", reply.message.text)
        assertEquals(StopReason.TOOL_CALLS, reply.stopReason)
        assertEquals("gpt-5.6-sol", reply.model)

        val call = reply.message.toolCalls.single()
        assertEquals("call-1", call.id)
        assertEquals("cats", call.arguments.getValue("query").jsonPrimitive.content)

        val reasoning = reply.message.parts.filterIsInstance<Part.Reasoning>().single()
        assertEquals(LlmProvider.OPENAI, reasoning.provider)
        assertEquals("opaque", reasoning.raw.getValue("encrypted_content").jsonPrimitive.content)

        assertEquals(120, reply.usage?.inputTokens)
        assertEquals(30, reply.usage?.outputTokens)
        assertEquals(100, reply.usage?.cacheReadTokens)
    }

    // a tool loop sends the reply back: reasoning items verbatim, function calls as items, results as outputs
    @Test
    fun `an assistant turn and its tool results replay in the shape the api returned them`() = runBlocking {
        val client = client()
        val first = client.complete(MODEL, request(*basic)).message
        sent.clear()

        client.complete(MODEL, request(*basic, first, Message.ToolResults(listOf(ToolResult("call-1", "lookUp", "found")))))

        val input = sent.single().getValue("input").jsonArray.map { it.jsonObject }
        val types = input.map { it.getValue("type").jsonPrimitive.content }

        assertEquals(listOf("message", "message", "reasoning", "message", "function_call", "function_call_output"), types)
        assertEquals("opaque", input[2].getValue("encrypted_content").jsonPrimitive.content)
        assertEquals("assistant", input[3].getValue("role").jsonPrimitive.content)
        assertEquals("output_text", input[3].getValue("content").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("""{"query":"cats"}""", input[4].getValue("arguments").jsonPrimitive.content)
        assertEquals("call-1", input[5].getValue("call_id").jsonPrimitive.content)
        assertEquals("found", input[5].getValue("output").jsonPrimitive.content)
    }

    @Test
    fun `a reasoning block another provider wrote is not replayed`() = runBlocking {
        val foreign = Message.Assistant(listOf(Part.Reasoning(LlmProvider.ANTHROPIC, buildJsonObject { put("type", "thinking") }), Part.Text("ok")))

        client().complete(MODEL, request(*basic, foreign, Message.User("next")))

        val types = sent.single().getValue("input").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content }
        assertEquals(listOf("message", "message", "message", "message"), types)
    }

    @Test
    fun `an image travels as a data url`() = runBlocking {
        client().complete(MODEL, request(Message.User(listOf(Part.Text("what is this"), Part.Image(byteArrayOf(1, 2, 3), "image/png", "a.png")))))

        val content = sent.single().getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("input_image", content[1].getValue("type").jsonPrimitive.content)
        assertTrue(content[1].getValue("image_url").jsonPrimitive.content.startsWith("data:image/png;base64,AQID"))
    }

    // gpt-5.6 reads nothing from the cache without explicit breakpoints; the two prefixes a turn re-sends get one
    @Test
    fun `a gpt-5_6 request with tools marks the system block and the current turn`() = runBlocking {
        client().complete(MODEL, request(*basic))

        val body = sent.single()
        assertEquals("explicit", body.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)

        val input = body.getValue("input").jsonArray.map { it.jsonObject }
        assertTrue(input[0].getValue("content").jsonArray.single().jsonObject.containsKey("prompt_cache_breakpoint"))
        assertTrue(input[1].getValue("content").jsonArray.single().jsonObject.containsKey("prompt_cache_breakpoint"))
    }

    @Test
    fun `a tool-free request marks the system block alone, and a recap marks nothing`() = runBlocking {
        val client = client()
        client.complete(MODEL, request(*basic, tools = emptyList()))

        val input = sent.single().getValue("input").jsonArray.map { it.jsonObject }
        assertTrue(input[0].getValue("content").jsonArray.single().jsonObject.containsKey("prompt_cache_breakpoint"))
        assertFalse(input[1].getValue("content").jsonArray.single().jsonObject.containsKey("prompt_cache_breakpoint"))
        sent.clear()

        client.complete(MODEL, request(*basic, options = RequestOptions(cachePrompt = false)))
        assertNull(sent.single()["prompt_cache_options"])
    }

    @Test
    fun `older models and other servers keep automatic caching`() = runBlocking {
        client().complete(OLD_MODEL, request(*basic))
        assertNull(sent.single()["prompt_cache_options"])
        sent.clear()

        client(explicitPromptCaching = false, statelessReasoning = false).complete(MODEL, request(*basic))
        assertNull(sent.single()["prompt_cache_options"])
        assertNull(sent.single()["store"])
        assertNull(sent.single()["include"])
    }

    @Test
    fun `explicit caching starts with the gpt-5_6 generation, minor version or not`() {
        assertTrue(supportsExplicitOpenAiPromptCaching("gpt-5.6-sol"))
        assertTrue(supportsExplicitOpenAiPromptCaching("gpt-6-luna"))
        assertTrue(supportsExplicitOpenAiPromptCaching("gpt-6.1-sol"))
        assertFalse(supportsExplicitOpenAiPromptCaching("gpt-5.5"))
        assertFalse(supportsExplicitOpenAiPromptCaching("gpt-5.4-mini"))
        assertFalse(supportsExplicitOpenAiPromptCaching("o3"))
    }

    @Test
    fun `a chat completions request and reply take the completions shape`() = runBlocking {
        val client = client(reply = COMPLETION_REPLY, endpoint = OpenAiEndpoint.COMPLETIONS, explicitPromptCaching = false, statelessReasoning = false)
        val reply = client.complete(OLD_MODEL, request(*basic, options = RequestOptions(reasoningEffort = ReasoningEffort.HIGH, parallelToolCalls = false)))

        val body = sent.single()
        assertEquals("high", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertEquals("system", body.getValue("messages").jsonArray.first().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("lookUp", body.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)

        assertEquals(StopReason.TOOL_CALLS, reply.stopReason)
        assertEquals("dogs", reply.message.toolCalls.single().arguments.getValue("query").jsonPrimitive.content)
        assertEquals(50, reply.usage?.inputTokens)
        assertEquals(40, reply.usage?.cacheReadTokens)
        sent.clear()

        // the thinking a server returned goes back with the call, and the results go back as tool messages
        client.complete(OLD_MODEL, request(*basic, reply.message, Message.ToolResults(listOf(ToolResult("call-9", "lookUp", "found")))))

        val messages = sent.single().getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals("thinking...", messages[2].getValue("reasoning_content").jsonPrimitive.content)
        assertEquals("call-9", messages[2].getValue("tool_calls").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("tool", messages[3].getValue("role").jsonPrimitive.content)
        assertEquals("call-9", messages[3].getValue("tool_call_id").jsonPrimitive.content)
    }

    @Test
    fun `a non-2xx answer is an error with its status and body`() = runBlocking {
        val failure = assertFailsWith<LlmException> { client(reply = """{"error":{"type":"usage_limit_reached"}}""", status = HttpStatusCode.TooManyRequests).complete(MODEL, request(*basic)) }

        assertEquals(429, failure.status)
        assertContains(failure.body.orEmpty(), "usage_limit_reached")
        assertContains(failure.message.orEmpty(), "429")
    }

    @Test
    fun `garbled tool arguments become an empty object rather than a failed call`() = runBlocking {
        val reply = client(reply = RESPONSES_REPLY.replace("""{\"query\":\"cats\"}""", "not json")).complete(MODEL, request(*basic))

        assertEquals(0, reply.message.toolCalls.single().arguments.size)
    }

    @Test
    fun `a streamed call is folded back into one response`() = runBlocking {
        val stream =
            """
            data: {"type":"response.created","response":{"id":"r"}}

            data: {"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"streamed"}]}}

            data: {"type":"response.completed","response":{"id":"r","status":"completed","model":"gpt-5.6-sol","output":[],"usage":{"input_tokens":5,"output_tokens":2}}}

            data: [DONE]
            """.trimIndent()

        val reply = client(reply = stream, streamed = true, explicitPromptCaching = false).complete(MODEL, request(*basic))

        assertEquals("streamed", reply.message.text)
        assertEquals(StopReason.END, reply.stopReason)
        assertEquals(5, reply.usage?.inputTokens)
        assertEquals(true, sent.single().getValue("stream").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a stream that fails or never completes is an error`() {
        val failed = assertFailsWith<LlmException> { collectStreamedResponse(listOf("""data: {"type":"response.failed","error":{"code":"server_is_overloaded"}}"""), "Codex") }
        assertContains(failed.body.orEmpty(), "server_is_overloaded")

        val cut = assertFailsWith<LlmException> { collectStreamedResponse(listOf("""data: {"type":"response.created"}"""), "Codex") }
        assertContains(cut.body.orEmpty(), "without a completed response")
    }

    @Test
    fun `a completed output is preserved when no item events were sent`() {
        val response =
            collectStreamedResponse(
                listOf("""data: {"type":"response.completed","response":{"id":"r","output":[{"type":"message","content":[]}]}}""", "", "not data"),
                "Codex",
            )

        assertEquals(1, response.getValue("output").jsonArray.size)
    }
}
