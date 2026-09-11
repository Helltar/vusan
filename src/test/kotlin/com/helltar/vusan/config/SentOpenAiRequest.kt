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
 * engine — after every serializer, after [transport], which wraps the client factory the way
 * `LlmRuntime` does for the destination under test, and inside the lenient decoding every runtime
 * client has.
 *
 * Params alone cannot show what koog drops or merges on the way out, and that is where both the
 * prompt-cache rewrite and the reasoning effort live. The reply comes back the way real servers send
 * it, see [openAiReplyTo], so a request that goes out fine but cannot be read back fails here too.
 */
internal suspend fun sentOpenAiRequest(
    runtime: LlmRuntime,
    transport: (KoogHttpClient.Factory) -> KoogHttpClient.Factory = { it }
): JsonObject {
    var sent: String? = null
    val responses = runtime.chatParams is OpenAIResponsesParams

    val engine =
        MockEngine { request ->
            val body = request.body.toByteArray().decodeToString().also { sent = it }

            respond(
                openAiReplyTo(body, responses),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

    val client =
        OpenAILLMClient(
            apiKey = "key",
            settings = OpenAIClientSettings(baseUrl = "https://api.openai.com"),
            httpClientFactory = LenientDecodingHttpClientFactory(transport(KtorKoogHttpClient.Factory(HttpClient(engine))))
        )

    val prompt =
        Prompt.build("wire", params = runtime.chatParams) {
            system("stable instructions")
            user("current request")
        }

    client.execute(prompt, runtime.model, emptyList())

    return Json.parseToJsonElement(assertNotNull(sent, "nothing reached the wire")).jsonObject
}

/**
 * A minimal successful reply to [requestBody]. A Responses reply repeats the reasoning config it was
 * sent, as OpenAI and the Codex backend both do, and koog parses that echo — a reply without it would
 * hide the one failure those servers actually produce.
 */
internal fun openAiReplyTo(requestBody: String, responses: Boolean): String {
    if (!responses) return CHAT_COMPLETION_REPLY

    val reasoning = Json.parseToJsonElement(requestBody).jsonObject["reasoning"] ?: return RESPONSES_REPLY

    return JsonObject(Json.parseToJsonElement(RESPONSES_REPLY).jsonObject + ("reasoning" to reasoning)).toString()
}

private const val CHAT_COMPLETION_REPLY =
    """{"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"gpt-5.6",""" +
            """"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}"""

private const val RESPONSES_REPLY =
    """{"id":"resp-1","object":"response","created_at":0,"model":"gpt-5.6","status":"completed",""" +
            """"parallel_tool_calls":false,"text":{},"output":[{"type":"message","id":"msg-1",""" +
            """"role":"assistant","status":"completed",""" +
            """"content":[{"type":"output_text","text":"ok","annotations":[]}]}]}"""
