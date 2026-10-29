package com.helltar.vusan.llm.openai

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MODEL = LlmModel("gpt-5.6-sol", contextWindowTokens = 1_050_000)
private val OLD_MODEL = LlmModel("gpt-5.4-mini", contextWindowTokens = 400_000)

private val TOOL =
    ToolDefinition(
        name = "lookUp",
        description = "Looks something up.",
        parameters = Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject,
    )

private val DRAW = TOOL.copy(name = "draw", deferred = true)
private val SING = TOOL.copy(name = "sing", deferred = true)
private val OFFERING_MODEL = MODEL.copy(offersToolsMidConversation = true)

private const val COMPLETION_REPLY =
    """{"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"deepseek-chat",
       "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"reasoning_content":"thinking...",
         "tool_calls":[{"id":"call-9","type":"function","function":{"name":"lookUp","arguments":"{\"query\":\"dogs\"}"}}]}}],
       "usage":{"prompt_tokens":50,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":40}}}"""

class ChatCompletionsClientTest {

    private val sent = mutableListOf<JsonObject>()

    private val basic = arrayOf(Message.System("stable instructions"), Message.User("current request"))

    private fun client(
        reply: String = COMPLETION_REPLY,
        status: HttpStatusCode = HttpStatusCode.OK,
        explicitPromptCaching: Boolean = false,
        echoesReasoningContent: Boolean = false,
        legacyOutputCeiling: Boolean = false,
    ): ChatCompletionsClient {
        val engine =
            MockEngine { request ->
                sent += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                respond(reply, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }

        return ChatCompletionsClient(
            http = HttpClient(engine),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "key",
            explicitPromptCaching = explicitPromptCaching,
            echoesReasoningContent = echoesReasoningContent,
            legacyOutputCeiling = legacyOutputCeiling,
        )
    }

    private fun request(vararg messages: Message, tools: List<ToolDefinition> = listOf(TOOL), options: RequestOptions = RequestOptions()) =
        ChatRequest(messages.toList(), tools, options)

    @Test
    fun `a chat completions request and reply take the completions shape`() = runBlocking {
        val client = client()
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

    // the same protocol is not the same reader: a fallback taking over mid-turn replays the other's reasoning
    @Test
    fun `reasoning another endpoint wrote is not replayed`() = runBlocking {
        val codex = Part.Reasoning("https://chatgpt.com/backend-api/codex/responses", buildJsonObject { put("type", "reasoning"); put("encrypted_content", "theirs") })
        val compatible = Part.Reasoning("https://api.deepseek.com/chat/completions", buildJsonObject { put("reasoning_content", "theirs") })
        val turn = Message.Assistant(listOf(codex, compatible, Part.Text("ok")))

        client().complete(MODEL, request(*basic, turn, Message.User("next")))
        assertFalse(sent.last().getValue("messages").jsonArray.any { "reasoning_content" in it.jsonObject })
    }

    // deepseek refuses a tool call of the turn that carries no `reasoning_content`, and one written by the
    // provider a fallback took over from has none of its own
    @Test
    fun `a call another endpoint wrote carries an empty reasoning to a server that echoes it`() = runBlocking {
        val foreign = Message.Assistant(listOf(Part.ToolCall("call-1", "lookUp", buildJsonObject { put("query", "cats") })))
        val turn = arrayOf(*basic, foreign, Message.ToolResults(listOf(ToolResult("call-1", "lookUp", "found"))))

        client(echoesReasoningContent = true).complete(MODEL, request(*turn))
        assertEquals("", sent.last().getValue("messages").jsonArray[2].jsonObject.getValue("reasoning_content").jsonPrimitive.content)

        client().complete(MODEL, request(*turn))
        assertFalse("reasoning_content" in sent.last().getValue("messages").jsonArray[2].jsonObject)
    }

    // both are refused in a request that defines no tools, and the choice keeps the replayed calls valid
    @Test
    fun `the tool choice and parallel calls travel only beside the tools`() = runBlocking {
        val options = RequestOptions(parallelToolCalls = false)

        client().complete(MODEL, request(*basic, tools = emptyList(), options = options))
        assertFalse("parallel_tool_calls" in sent.last())
        assertFalse("tool_choice" in sent.last())

        client().complete(MODEL, ChatRequest(basic.toList(), listOf(TOOL), options, mayCallTools = false))
        assertEquals(false, sent.last().getValue("parallel_tool_calls").jsonPrimitive.content.toBoolean())
        assertEquals("none", sent.last().getValue("tool_choice").jsonPrimitive.content)
    }

    // openai renamed the ceiling and refuses the old name on a reasoning model; deepseek reads only the old one
    @Test
    fun `a completions ceiling takes the name its server reads`() = runBlocking {
        val options = RequestOptions(maxOutputTokens = 40)

        client().complete(OLD_MODEL, request(*basic, options = options))
        assertEquals(40, sent.last().getValue("max_completion_tokens").jsonPrimitive.content.toInt())
        assertNull(sent.last()["max_tokens"])

        client(legacyOutputCeiling = true).complete(OLD_MODEL, request(*basic, options = options))
        assertEquals(40, sent.last().getValue("max_tokens").jsonPrimitive.content.toInt())
        assertNull(sent.last()["max_completion_tokens"])
    }

    @Test
    fun `an image travels as a data url beside the text`() = runBlocking {
        client().complete(MODEL, request(Message.User(listOf(Part.Text("what is this"), Part.Image(byteArrayOf(1, 2, 3), "image/png", "a.png")))))

        val content = sent.single().getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("image_url", content[1].getValue("type").jsonPrimitive.content)
        assertTrue(content[1].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content.startsWith("data:image/png;base64,AQID"))
    }

    @Test
    fun `an offered tool is listed up front and the offer left out`() = runBlocking {
        client().complete(OFFERING_MODEL, request(*basic, Message.ToolsOffered(listOf("draw")), tools = listOf(TOOL, DRAW, SING)))

        val body = sent.single()
        assertEquals(listOf("lookUp", "draw"), body.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("system", "user"), body.getValue("messages").jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
    }

    @Test
    fun `the official api gets the cache options, other servers none`() = runBlocking {
        client(explicitPromptCaching = true).complete(MODEL, request(*basic))
        assertEquals("implicit", sent.last().getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)

        client().complete(MODEL, request(*basic))
        assertNull(sent.last()["prompt_cache_options"])
    }

    // deepseek cuts an answer short when its servers run out of capacity: an overload to repeat, not a reply
    @Test
    fun `an answer cut for want of capacity is an overload`() = runBlocking {
        val cut = COMPLETION_REPLY.replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"insufficient_system_resource\"")

        val failure = assertFailsWith<LlmException> { client(reply = cut).complete(MODEL, request(*basic)) }

        assertEquals(503, failure.status)
    }

    @Test
    fun `a non-2xx answer is an error with its status and body`() = runBlocking {
        val failure = assertFailsWith<LlmException> { client(reply = """{"error":{"message":"Insufficient Balance"}}""", status = HttpStatusCode.PaymentRequired).complete(MODEL, request(*basic)) }

        assertEquals(402, failure.status)
        assertContains(failure.body.orEmpty(), "Insufficient Balance")
    }

    @Test
    fun `garbled tool arguments become an empty object rather than a failed call`() = runBlocking {
        val reply = client(reply = COMPLETION_REPLY.replace("""{\"query\":\"dogs\"}""", "not json")).complete(MODEL, request(*basic))

        assertEquals(0, reply.message.toolCalls.single().arguments.size)
    }
}
