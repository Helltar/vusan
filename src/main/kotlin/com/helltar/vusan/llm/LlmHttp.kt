package com.helltar.vusan.llm

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.AttributeKey
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration

// one Json for every client: lenient on the way in, since a provider adds fields faster than anyone reads
// them, and nothing a client decodes is a closed shape.
internal val llmJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/**
 * A Ktor client for one provider: the timeouts a model call may take, and nothing else assumed.
 *
 * Both request and socket timeouts are the one the operator set, so a stalled call fails and the agent
 * delivers an error reply instead of leaving the bot silent for the engine's quarter of an hour.
 */
fun llmHttpClient(requestTimeout: Duration, plugin: ClientPlugin<Unit>? = null): HttpClient =
    HttpClient(CIO) {
        expectSuccess = false

        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeout.inWholeMilliseconds
            socketTimeoutMillis = requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
        }

        plugin?.let { install(it) }
    }

private const val CONNECT_TIMEOUT_MILLIS = 10_000L
private const val SSE_DATA_PREFIX = "data:"

/** The conversation a request belongs to, for a plugin that files requests per conversation without reading the body back. */
internal val PROMPT_CACHE_KEY = AttributeKey<String>("promptCacheKey")

// a stream that ended in an error event answered with a 200, so the status alone says nothing
internal const val STREAM_ERROR_STATUS = 200

/** The JSON of one `data:` line of an event stream, or `null` for any other line, an empty one and `[DONE]`. */
internal fun String.ssePayloadOrNull(): JsonObject? {
    if (!startsWith(SSE_DATA_PREFIX)) return null

    val payload = removePrefix(SSE_DATA_PREFIX).trim()
    if (payload.isEmpty() || payload == "[DONE]") return null

    return runCatching { llmJson.parseToJsonElement(payload).jsonObject }.getOrNull()
}

private fun HttpRequestBuilder.carryPromptCacheKey(body: JsonObject) {
    body.string("prompt_cache_key")?.let { attributes.put(PROMPT_CACHE_KEY, it) }
}

/**
 * Posts [body] and reads the answer as JSON, turning anything but a 2xx — and anything that never
 * answered — into an [LlmException] the rules downstream can read a status and a body out of.
 */
internal suspend fun HttpClient.postJson(
    provider: String,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
): JsonObject {
    val response =
        try {
            post(url) {
                contentType(ContentType.Application.Json)
                headers.forEach { (name, value) -> header(name, value) }
                carryPromptCacheKey(body)
                setBody(body.toString())
            }
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            throw LlmException(provider, status = null, body = e.message, cause = e)
        }

    val text = response.bodyAsText()
    val parsed = if (response.status.isSuccess()) runCatching { llmJson.parseToJsonElement(text).jsonObject }.getOrNull() else null

    return parsed
        ?: throw LlmException(
            provider,
            response.status.value,
            if (response.status.isSuccess()) "unreadable reply: ${text.take(REPLY_PREVIEW_CHARS)}" else text,
        )
}

private const val REPLY_PREVIEW_CHARS = 500

/**
 * Posts [body] as a streaming request and hands every `data:` payload of the event stream to [onEvent]
 * as it arrives. A non-2xx answer is an [LlmException] with the body the server sent instead of a stream.
 */
internal suspend fun HttpClient.postEventStream(
    provider: String,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
    onEvent: (JsonObject) -> Unit,
) {
    try {
        prepareStatement(url, body, headers).execute { response ->
            response.failUnlessSuccess(provider)

            val channel = response.bodyAsChannel()

            while (!channel.isClosedForRead) {
                // the event stream spec ends a line with LF, CRLF or a bare CR; the default mode drops the last
                val line = channel.readLine(LineEnding.Lenient) ?: break
                line.ssePayloadOrNull()?.let(onEvent)
            }
        }
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
        throw e as? LlmException ?: LlmException(provider, status = null, body = e.message, cause = e)
    }
}

// a non-2xx answer to a streaming request carries the error body instead of a stream
private suspend fun HttpResponse.failUnlessSuccess(provider: String) {
    if (!status.isSuccess()) throw LlmException(provider, status.value, bodyAsText())
}

private suspend fun HttpClient.prepareStatement(url: String, body: JsonObject, headers: Map<String, String>): HttpStatement =
    preparePost(url) {
        contentType(ContentType.Application.Json)
        accept(ContentType.Text.EventStream)
        headers.forEach { (name, value) -> header(name, value) }
        carryPromptCacheKey(body)
        setBody(body.toString())
    }
