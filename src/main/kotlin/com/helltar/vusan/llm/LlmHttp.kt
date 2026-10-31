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

// one Json for every client, lenient on input: providers add fields faster than anyone reads them, and no
// decoded shape is closed.
internal val llmJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/**
 * A Ktor client for one provider with the timeouts a model call may take, nothing else assumed. Request and
 * socket timeouts are both the operator's, so a stalled call fails with an error reply instead of a quarter
 * hour of silence.
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

/**
 * The conversation a request belongs to, for a plugin filing requests per conversation without reading the
 * body back.
 */
internal val PROMPT_CACHE_KEY = AttributeKey<String>("promptCacheKey")

// a stream ending in an error event answered 200, so the status says nothing
internal const val STREAM_ERROR_STATUS = 200

/** The JSON of one `data:` line of an event stream, or `null` for other lines, empty ones and `[DONE]`. */
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
 * Posts [body] and reads the JSON answer, turning a non-2xx, or no answer at all, into an [LlmException] with
 * a readable status and body.
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
 * Posts [body] as a streaming request and hands each `data:` payload to [onEvent] as it arrives. A non-2xx
 * answer is an [LlmException] with the body sent instead of a stream.
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
                // event streams end lines with LF, CRLF or a bare CR; the default mode drops the last
                val line = channel.readLine(LineEnding.Lenient) ?: break
                line.ssePayloadOrNull()?.let(onEvent)
            }
        }
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
        throw e as? LlmException ?: LlmException(provider, status = null, body = e.message, cause = e)
    }
}

// a non-2xx answer to a streaming request carries an error body instead of a stream
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
