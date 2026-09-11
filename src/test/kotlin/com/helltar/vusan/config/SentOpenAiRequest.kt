package com.helltar.vusan.config

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertNotNull

/**
 * The body koog's own OpenAI client sends for [runtime]'s model and chat params, read off the HTTP
 * engine — after every serializer, and after [transport], which wraps the client factory the way
 * `LlmRuntime` does for the destination under test.
 *
 * Params alone cannot show what koog drops or merges on the way out, and that is where both the
 * prompt-cache rewrite and the reasoning effort live.
 */
internal suspend fun sentOpenAiRequest(
    runtime: LlmRuntime,
    transport: (KoogHttpClient.Factory) -> KoogHttpClient.Factory = { it }
): JsonObject {
    var sent: String? = null
    val reply = if (runtime.chatParams is OpenAIResponsesParams) RESPONSES_REPLY else CHAT_COMPLETION_REPLY

    val engine =
        MockEngine { request ->
            sent = request.body.toByteArray().decodeToString()
            respond(reply, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }

    val client =
        OpenAILLMClient(
            apiKey = "key",
            settings = OpenAIClientSettings(baseUrl = "https://api.openai.com"),
            httpClientFactory = transport(KtorKoogHttpClient.Factory(HttpClient(engine)))
        )

    val prompt =
        Prompt.build("wire", params = runtime.chatParams) {
            system("stable instructions")
            user("current request")
        }

    client.execute(prompt, runtime.model, emptyList())

    return Json.parseToJsonElement(assertNotNull(sent, "nothing reached the wire")).jsonObject
}

private const val CHAT_COMPLETION_REPLY =
    """{"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"gpt-5.6",""" +
            """"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}"""

private const val RESPONSES_REPLY =
    """{"id":"resp-1","object":"response","created_at":0,"model":"gpt-5.6","status":"completed",""" +
            """"parallel_tool_calls":false,"text":{},"output":[{"type":"message","id":"msg-1",""" +
            """"role":"assistant","status":"completed",""" +
            """"content":[{"type":"output_text","text":"ok","annotations":[]}]}]}"""
