package com.helltar.vusan.config

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpenAiPromptCachingTest {

    @Test
    fun `responses request caches only the stable developer prefix`() {
        val request =
            """
            {
              "model": "gpt-5.6-terra",
              "input": [
                {
                  "type": "message",
                  "role": "developer",
                  "content": [{"type": "input_text", "text": "stable instructions"}]
                },
                {
                  "type": "message",
                  "role": "system",
                  "content": [{"type": "input_text", "text": "current time: changes"}]
                },
                {
                  "type": "message",
                  "role": "user",
                  "content": [{"type": "input_text", "text": "current request"}]
                }
              ]
            }
            """.trimIndent()

        val root = transformed(request)
        val input = root["input"]!!.jsonArray
        val stableBlock = input[0].jsonObject["content"]!!.jsonArray.single().jsonObject
        val dynamicBlock = input[1].jsonObject["content"]!!.jsonArray.single().jsonObject

        assertEquals("explicit", root["prompt_cache_options"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(
            "explicit",
            stableBlock["prompt_cache_breakpoint"]!!.jsonObject["mode"]!!.jsonPrimitive.content
        )
        assertFalse("prompt_cache_breakpoint" in dynamicBlock)
    }

    @Test
    fun `chat request turns stable system text into a marked content block`() {
        val request =
            """
            {
              "model": "gpt-5.6",
              "messages": [
                {"role": "system", "content": "stable instructions"},
                {"role": "user", "content": "current request"}
              ]
            }
            """.trimIndent()

        val root = transformed(request)
        val block = root["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.single().jsonObject

        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("stable instructions", block["text"]!!.jsonPrimitive.content)
        assertEquals(
            "explicit",
            block["prompt_cache_breakpoint"]!!.jsonObject["mode"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `a tool request also caches through the current user turn`() {
        val request =
            """
            {
              "model": "gpt-5.6",
              "tools": [{"type": "function", "function": {"name": "sendMessage"}}],
              "messages": [
                {"role": "system", "content": "stable instructions"},
                {"role": "user", "content": "older turn"},
                {"role": "system", "content": "current time: changes"},
                {"role": "user", "content": "current request"}
              ]
            }
            """.trimIndent()

        val messages = transformed(request)["messages"]!!.jsonArray

        assertTrue(messages[0].isCacheBreakpoint(), "the system prefix lost its breakpoint")
        assertTrue(messages[3].isCacheBreakpoint(), "the current turn was not marked")

        // the run re-sends everything before the current turn on every tool iteration, so only these
        // two positions are worth a write; a middle one would spend a slot on a prefix nobody rereads.
        assertFalse(messages[1].isCacheBreakpoint())
        assertFalse(messages[2].isCacheBreakpoint())
    }

    @Test
    fun `a tool-free request caches only the system prefix`() {
        val request =
            """
            {
              "model": "gpt-5.6",
              "messages": [
                {"role": "system", "content": "recap instructions"},
                {"role": "user", "content": "events to summarize"}
              ]
            }
            """.trimIndent()

        val messages = transformed(request)["messages"]!!.jsonArray

        assertTrue(messages[0].isCacheBreakpoint())

        // a recap prompt runs once and its user message never repeats, so marking it would buy a
        // cache write that is never read.
        assertFalse(messages[1].isCacheBreakpoint())
    }

    @Test
    fun `request stays implicit when no stable system prefix exists`() {
        val request = """{"model":"gpt-5.6","input":[{"role":"user","content":"hello"}]}"""

        assertEquals(request, addExplicitOpenAiPromptCacheBreakpoint(request))
    }

    @Test
    fun `older OpenAI models keep automatic prompt caching`() {
        val request = """{"model":"gpt-5.5","messages":[{"role":"system","content":"stable"}]}"""

        assertEquals(request, addExplicitOpenAiPromptCacheBreakpoint(request))
    }

    @Test
    fun `explicit caching starts with the GPT 5_6 family`() {
        assertFalse(supportsExplicitOpenAiPromptCaching("gpt-5.5"))
        assertTrue(supportsExplicitOpenAiPromptCaching("gpt-5.6-luna"))
        assertTrue(supportsExplicitOpenAiPromptCaching("GPT-6.0"))
        assertFalse(supportsExplicitOpenAiPromptCaching("grok-5.6"))
    }

    @Test
    fun `a chat completions request reaches the wire with its cache breakpoints`() = runBlocking {
        assertExplicitlyCached(sentBody(OpenAiEndpoint.COMPLETIONS, CHAT_COMPLETION_REPLY), "messages")
    }

    @Test
    fun `a responses request reaches the wire with its cache breakpoints`() = runBlocking {
        assertExplicitlyCached(sentBody(OpenAiEndpoint.RESPONSES, RESPONSES_REPLY), "input")
    }

    private fun transformed(request: String) =
        Json.parseToJsonElement(addExplicitOpenAiPromptCacheBreakpoint(request)).jsonObject

    // an unmarked message keeps its plain string content; marking is what turns it into text blocks.
    private fun JsonElement.isCacheBreakpoint(): Boolean =
        (jsonObject["content"] as? JsonArray)?.any { "prompt_cache_breakpoint" in it.jsonObject } == true

    // koog's own client over the bridge, as LlmRuntime wires it. the bridge only rewrites a body koog
    // hands over as a string and forwards anything else untouched, so a koog bump that changes the body
    // type turns caching off silently — the unit tests above would all stay green.
    private suspend fun sentBody(endpoint: OpenAiEndpoint, reply: String): JsonObject {
        var sent: String? = null

        val engine =
            MockEngine { request ->
                sent = request.body.toByteArray().decodeToString()
                respond(reply, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }

        val runtime =
            resolveLlmRuntime(
                LlmProviderConfig.OpenAiCompatible(
                    baseUrl = OPENAI_BASE_URL,
                    apiKey = "key",
                    model = "gpt-5.6",
                    endpoint = endpoint,
                    reasoningEffort = null,
                    requestTimeout = 120.seconds,
                    contextWindowTokens = null
                )
            )

        val client =
            OpenAILLMClient(
                apiKey = "key",
                settings = OpenAIClientSettings(baseUrl = OPENAI_BASE_URL),
                httpClientFactory = OpenAiPromptCachingHttpClientFactory(KtorKoogHttpClient.Factory(HttpClient(engine)))
            )

        val prompt =
            Prompt.build("caching", params = runtime.chatParams) {
                system("stable instructions")
                user("current request")
            }

        client.execute(prompt, runtime.model, emptyList())

        return Json.parseToJsonElement(assertNotNull(sent, "nothing reached the wire")).jsonObject
    }

    private fun assertExplicitlyCached(body: JsonObject, messagesKey: String) {
        assertEquals("vusan", body.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals("explicit", body.getValue("prompt_cache_options").jsonObject.getValue("mode").jsonPrimitive.content)
        assertTrue(body.getValue(messagesKey).jsonArray.any { it.isCacheBreakpoint() }, "no message carries a breakpoint")
    }

    private companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com"

        const val CHAT_COMPLETION_REPLY =
            """{"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"gpt-5.6",""" +
                    """"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}"""

        const val RESPONSES_REPLY =
            """{"id":"resp-1","object":"response","created_at":0,"model":"gpt-5.6","status":"completed",""" +
                    """"parallel_tool_calls":false,"text":{},"output":[{"type":"message","id":"msg-1",""" +
                    """"role":"assistant","status":"completed",""" +
                    """"content":[{"type":"output_text","text":"ok","annotations":[]}]}]}"""
    }
}
