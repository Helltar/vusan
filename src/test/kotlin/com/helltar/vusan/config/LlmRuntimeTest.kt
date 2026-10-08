package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmProvider
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.ToolDefinition
import com.helltar.vusan.llm.openai.OpenAiEndpoint
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val RESPONSES_REPLY =
    """{"id":"r","object":"response","status":"completed","model":"gpt-5.6-sol","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"ok"}]}],"usage":{"input_tokens":1,"output_tokens":1}}"""

private const val COMPLETION_REPLY =
    """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""

private const val ANTHROPIC_REPLY =
    """{"id":"m","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""

private const val CODEX_STREAM =
    "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"status\":\"completed\",\"model\":\"gpt-5.6-terra\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"

/**
 * What each provider's runtime puts on the wire, read off a mock engine: the resolver's choices — endpoint,
 * options, model shape — matter only as the request they produce.
 */
class LlmRuntimeTest {

    private class Wire(val url: String, val headers: Headers, val body: JsonObject)

    private val sent = mutableListOf<Wire>()

    private fun http(reply: String, contentType: String = ContentType.Application.Json.toString()): HttpClient =
        HttpClient(
            MockEngine { request ->
                sent += Wire(request.url.toString(), request.headers, Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
                respond(reply, headers = headersOf(HttpHeaders.ContentType, contentType))
            },
        )

    private val tool = ToolDefinition("lookUp", "Looks up.", buildJsonObject { put("type", "object") })

    private fun request(options: RequestOptions, tools: List<ToolDefinition> = listOf(tool)) =
        ChatRequest(listOf(Message.System("stable instructions"), Message.User("current request")), tools, options)

    private fun openAi(model: String = "gpt-5.6-sol", effort: ReasoningEffort? = null, window: Long? = null) =
        LlmProviderConfig.OpenAi(apiKey = "key", model = model, reasoningEffort = effort, requestTimeout = TIMEOUT, contextWindowTokens = window)

    private fun anthropic(model: String = "claude-opus-5-5", effort: ReasoningEffort? = null, window: Long? = null, takesEffort: Boolean? = null) =
        LlmProviderConfig.Anthropic(apiKey = "key", model = model, reasoningEffort = effort, requestTimeout = TIMEOUT, contextWindowTokens = window, takesEffort = takesEffort)

    private fun compatible(baseUrl: String = "https://example.test", endpoint: OpenAiEndpoint = OpenAiEndpoint.COMPLETIONS, effort: ReasoningEffort? = null, window: Long? = null) =
        LlmProviderConfig.OpenAiCompatible(baseUrl = baseUrl, apiKey = "key", model = "deepseek-chat", endpoint = endpoint, reasoningEffort = effort, requestTimeout = TIMEOUT, contextWindowTokens = window)

    private fun codex(effort: ReasoningEffort? = null, verbosity: String? = null, tier: ServiceTier? = null, vision: Boolean = true, window: Long? = null) =
        LlmProviderConfig.Codex(model = "gpt-5.6-terra", reasoningEffort = effort, serviceTier = tier, verbosity = verbosity, supportsVision = vision, requestTimeout = TIMEOUT, contextWindowTokens = window)

    private fun codexAuth() = CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }))

    // --- openai ---

    @Test
    fun `an openai runtime speaks responses with its own cache key, stateless reasoning and the effort`() = runBlocking {
        val runtime = resolveLlmRuntime(openAi(effort = ReasoningEffort.XHIGH), http = http(RESPONSES_REPLY))

        assertEquals("OpenAI", runtime.providerLabel)
        assertEquals(LlmProvider.OPENAI, runtime.model.provider)
        assertEquals(1_050_000, runtime.model.contextWindowTokens)
        assertTrue(runtime.model.seesImages)
        assertEquals("xhigh", runtime.reasoningEffort)

        runtime.client.complete(runtime.model, request(runtime.chatOptions))

        val wire = sent.single()
        assertEquals("https://api.openai.com/v1/responses", wire.url)
        assertEquals("Bearer key", wire.headers["Authorization"])
        assertEquals("vusan", wire.body.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals("xhigh", wire.body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals(false, wire.body.getValue("store").jsonPrimitive.content.toBoolean())
        assertEquals("implicit", wire.body.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)
    }

    @Test
    fun `the recap keeps its own key and asks for no caching`() = runBlocking {
        val runtime = resolveLlmRuntime(openAi(), http = http(RESPONSES_REPLY))

        runtime.client.complete(runtime.model, request(runtime.compactionOptions, tools = emptyList()))

        val body = sent.single().body
        assertEquals("vusan-recap", body.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals("explicit", body.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)
        assertFalse(body.getValue("input").jsonArray.any { item -> item.jsonObject.getValue("content").jsonArray.any { "prompt_cache_breakpoint" in it.jsonObject } })
    }

    @Test
    fun `a configured context window overrides the assumed one`() {
        assertEquals(200_000, resolveLlmRuntime(openAi(window = 200_000)).model.contextWindowTokens)
        assertEquals(50_000, resolveLlmRuntime(anthropic(window = 50_000)).model.contextWindowTokens)
        assertEquals(32_768, resolveLlmRuntime(compatible(window = 32_768)).model.contextWindowTokens)
    }

    // the gpt-4 family does not reason: it is asked for no encrypted reasoning, and an effort for it is a mistake
    @Test
    fun `an openai model that does not reason takes no effort`() {
        assertFalse(resolveLlmRuntime(openAi(model = "gpt-4.1-mini")).model.takesEffort)
        assertTrue(resolveLlmRuntime(openAi(model = "gpt-5.6-sol")).model.takesEffort)
        assertFailsWith<IllegalArgumentException> { resolveLlmRuntime(openAi(model = "gpt-4.1", effort = ReasoningEffort.LOW)) }
    }

    @Test
    fun `each conversation gets a prompt cache key of its own`() {
        val base = resolveLlmRuntime(openAi()).chatOptions

        val one = base.forConversation("telegram:1@-100").promptCacheKey
        val again = base.forConversation("telegram:1@-100").promptCacheKey
        val other = base.forConversation("telegram:2@-100").promptCacheKey

        assertEquals(one, again)
        assertNotEquals(one, other)
        assertTrue(one.orEmpty().startsWith("vusan-"))
    }

    // --- anthropic ---

    @Test
    fun `an anthropic runtime asks for adaptive thinking, the effort, the ceiling and two breakpoints`() = runBlocking {
        val runtime = resolveLlmRuntime(anthropic(effort = ReasoningEffort.HIGH), http = http(ANTHROPIC_REPLY))

        assertEquals("Anthropic", runtime.providerLabel)
        assertEquals(1_000_000, runtime.model.contextWindowTokens)
        assertEquals(128_000, runtime.model.maxOutputTokens)
        assertTrue(runtime.model.takesEffort)
        assertNull(runtime.chatOptions.promptCacheKey)

        runtime.client.complete(runtime.model, request(runtime.chatOptions))

        val wire = sent.single()
        assertEquals("https://api.anthropic.com/v1/messages", wire.url)
        assertEquals("key", wire.headers["x-api-key"])
        assertEquals("thinking-binding-controls-2026-08-01", wire.headers["anthropic-beta"])
        assertEquals("adaptive", wire.body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("high", wire.body.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals(128_000, wire.body.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertEquals("ephemeral", wire.body.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("1h", wire.body.getValue("system").jsonArray.single().jsonObject.getValue("cache_control").jsonObject.getValue("ttl").jsonPrimitive.content)
        sent.clear()

        runtime.client.complete(runtime.model, request(runtime.compactionOptions, tools = emptyList()))
        assertNull(sent.single().body["cache_control"])
        assertEquals("high", sent.single().body.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    // the api refuses `adaptive` and `effort` on every model it still serves under a dated snapshot id
    @Test
    fun `a claude model under a dated id takes neither thinking nor an effort`() = runBlocking {
        assertFalse(anthropicTakesEffort("claude-haiku-4-5-20251001"))
        assertFalse(anthropicTakesEffort("claude-sonnet-4-5-20250929"))
        assertTrue(anthropicTakesEffort("claude-opus-4-7"))
        assertTrue(anthropicTakesEffort("claude-haiku-5-5"))

        val runtime = resolveLlmRuntime(anthropic(model = "claude-haiku-4-5-20251001"), http = http(ANTHROPIC_REPLY))
        assertFalse(runtime.model.takesEffort)

        runtime.client.complete(runtime.model, request(runtime.chatOptions))
        assertNull(sent.single().body["thinking"])

        assertFailsWith<IllegalArgumentException> { resolveLlmRuntime(anthropic(model = "claude-haiku-4-5-20251001", effort = ReasoningEffort.HIGH)) }
    }

    @Test
    fun `what the vendor's list said about a model wins over the assumption`() {
        val runtime = resolveLlmRuntime(anthropic(model = "claude-haiku-4-5-20251001", takesEffort = true, window = 200_000).copy(maxOutputTokens = 64_000))

        assertTrue(runtime.model.takesEffort)
        assertEquals(64_000, runtime.model.maxOutputTokens)
    }

    @Test
    fun `an effort anthropic does not take fails at startup rather than on the first turn`() {
        for (effort in listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL)) {
            assertFailsWith<IllegalArgumentException>(effort.name) { resolveLlmRuntime(anthropic(effort = effort)) }
        }
    }

    // --- openai-compatible ---

    @Test
    fun `a compatible runtime speaks completions under its base url, parallel calls off, no openai extras`() = runBlocking {
        val runtime = resolveLlmRuntime(compatible(effort = ReasoningEffort.HIGH), http = http(COMPLETION_REPLY))

        assertEquals("OpenAI-compatible (https://example.test, completions)", runtime.providerLabel)
        assertEquals(16_384, runtime.model.contextWindowTokens, "a server whose window nobody declared runs on the policy's default")
        assertFalse(runtime.model.seesImages)

        runtime.client.complete(runtime.model, request(runtime.chatOptions))

        val wire = sent.single()
        assertEquals("https://example.test/v1/chat/completions", wire.url)
        assertEquals(false, wire.body.getValue("parallel_tool_calls").jsonPrimitive.content.toBoolean())
        assertEquals("high", wire.body.getValue("reasoning_effort").jsonPrimitive.content)
        assertNull(wire.body["prompt_cache_key"])
        assertNull(wire.body["store"])
        assertNull(wire.body["prompt_cache_options"])
    }

    @Test
    fun `a compatible runtime targets the responses endpoint on request`() = runBlocking {
        val runtime = resolveLlmRuntime(compatible(endpoint = OpenAiEndpoint.RESPONSES), http = http(RESPONSES_REPLY))

        runtime.client.complete(runtime.model, request(runtime.chatOptions))

        assertEquals("https://example.test/v1/responses", sent.single().url)
        assertNull(sent.single().body["include"], "a third-party server may not know the field")
    }

    @Test
    fun `the official api behind the compatible provider gets the openai extras`() = runBlocking {
        val runtime = resolveLlmRuntime(compatible(baseUrl = "https://api.openai.com/", endpoint = OpenAiEndpoint.RESPONSES), http = http(RESPONSES_REPLY))

        runtime.client.complete(runtime.model, request(runtime.chatOptions))

        assertEquals("https://api.openai.com/v1/responses", sent.single().url)
        assertEquals("vusan", sent.single().body.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals(false, sent.single().body.getValue("store").jsonPrimitive.content.toBoolean())
    }

    // --- codex ---

    @Test
    fun `a codex runtime streams the responses api without an api key and with the catalog's options`() = runBlocking {
        val runtime =
            resolveLlmRuntime(
                codex(effort = ReasoningEffort.XHIGH, verbosity = "low", tier = ServiceTier.PRIORITY, vision = false, window = 272_000),
                codexAuth = codexAuth(),
                http = http(CODEX_STREAM, contentType = "text/event-stream"),
            )

        assertEquals("ChatGPT subscription (Codex)", runtime.providerLabel)
        assertEquals(272_000, runtime.model.contextWindowTokens)
        assertFalse(runtime.model.seesImages)
        assertEquals("priority", runtime.serviceTier)

        val reply = runtime.client.complete(runtime.model, request(runtime.chatOptions))

        assertEquals("ok", reply.message.text)
        val wire = sent.single()
        assertEquals("$CODEX_BACKEND_BASE_URL/responses", wire.url)
        assertNull(wire.headers["Authorization"], "the plugin signs the request, not the client; the test client has no plugin")
        assertEquals(true, wire.body.getValue("stream").jsonPrimitive.content.toBoolean())
        assertEquals(false, wire.body.getValue("store").jsonPrimitive.content.toBoolean())
        assertEquals("xhigh", wire.body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("low", wire.body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
        assertEquals("priority", wire.body.getValue("service_tier").jsonPrimitive.content)
        assertEquals("vusan", wire.body.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals(false, wire.body.getValue("parallel_tool_calls").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a codex runtime needs the signed-in account`() {
        assertFailsWith<IllegalArgumentException> { resolveLlmRuntime(codex()) }
    }

    @Test
    fun `codex chat and compaction prompts use separate cache keys`() {
        val runtime = resolveLlmRuntime(codex(), codexAuth = codexAuth())

        assertEquals("vusan", runtime.chatOptions.promptCacheKey)
        assertEquals("vusan-recap", runtime.compactionOptions.promptCacheKey)
        assertFalse(runtime.compactionOptions.cachePrompt)
    }

    @Test
    fun `a configured serving tier reaches the routing hint`() {
        assertEquals("model=gpt-5.6-terra", codexRoutingHint("gpt-5.6-terra", null))
        assertEquals("model=gpt-5.6-terra;tier=priority", codexRoutingHint("gpt-5.6-terra", ServiceTier.PRIORITY))
    }

    private companion object {
        val TIMEOUT = 120.seconds
    }
}
