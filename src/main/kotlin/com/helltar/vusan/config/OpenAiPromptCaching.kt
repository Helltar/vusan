package com.helltar.vusan.config

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.reflect.KClass

// koog 1.2.0 exposes prompt_cache_key but not gpt-5.6 cache breakpoints, so rewrite only official
// openai requests until the client can represent these fields itself.
internal class OpenAiPromptCachingHttpClientFactory(
    private val delegate: KoogHttpClient.Factory
) : KoogHttpClient.Factory {

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json
    ): KoogHttpClient =
        OpenAiPromptCachingHttpClient(
            delegate =
                delegate.create(
                    clientName = clientName,
                    baseUrl = baseUrl,
                    headers = headers,
                    queryParameters = queryParameters,
                    requestTimeoutMillis = requestTimeoutMillis,
                    connectTimeoutMillis = connectTimeoutMillis,
                    socketTimeoutMillis = socketTimeoutMillis,
                    json = json
                ),
            json = json
        )
}

private class OpenAiPromptCachingHttpClient(
    private val delegate: KoogHttpClient,
    private val json: Json
) : KoogHttpClient {

    override val clientName: String = delegate.clientName

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): R = delegate.get(path, responseType, parameters, headers)

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): R =
        if (requestBody is String) {
            delegate.post(
                path = path,
                requestBody = addExplicitOpenAiPromptCacheBreakpoint(requestBody, json),
                requestBodyType = String::class,
                responseType = responseType,
                parameters = parameters,
                headers = headers
            )
        } else {
            delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)
        }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): Flow<O> =
        if (requestBody is String) {
            delegate.sse(
                path = path,
                requestBody = addExplicitOpenAiPromptCacheBreakpoint(requestBody, json),
                requestBodyType = String::class,
                dataFilter = dataFilter,
                decodeStreamingResponse = decodeStreamingResponse,
                processStreamingChunk = processStreamingChunk,
                parameters = parameters,
                headers = headers
            )
        } else {
            delegate.sse(
                path,
                requestBody,
                requestBodyType,
                dataFilter,
                decodeStreamingResponse,
                processStreamingChunk,
                parameters,
                headers
            )
        }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): Flow<String> =
        if (requestBody is String) {
            delegate.lines(
                path = path,
                requestBody = addExplicitOpenAiPromptCacheBreakpoint(requestBody, json),
                requestBodyType = String::class,
                parameters = parameters,
                headers = headers
            )
        } else {
            delegate.lines(path, requestBody, requestBodyType, parameters, headers)
        }

    override fun close() = delegate.close()
}

internal fun addExplicitOpenAiPromptCacheBreakpoint(requestBody: String, json: Json = Json): String {
    val root = json.parseToJsonElement(requestBody) as? JsonObject ?: return requestBody
    val model = (root["model"] as? JsonPrimitive)?.contentOrNull ?: return requestBody
    if (!supportsExplicitOpenAiPromptCaching(model)) return requestBody

    // the current turn is only worth its own cache write when the request can start a tool loop that
    // re-sends it: a tool-free prompt (the history recap) never repeats its user message.
    val markCurrentTurn = (root["tools"] as? JsonArray)?.isNotEmpty() == true

    val markedMessages =
        (root["input"] as? JsonArray)
            ?.let { markCachedPrefixes(it, "input_text", markCurrentTurn) }
            ?.let { "input" to it }
            ?: (root["messages"] as? JsonArray)
                ?.let { markCachedPrefixes(it, "text", markCurrentTurn) }
                ?.let { "messages" to it }
            ?: return requestBody

    return JsonObject(root + markedMessages + ("prompt_cache_options" to EXPLICIT_CACHE_CONTROL)).toString()
}

internal fun supportsExplicitOpenAiPromptCaching(model: String): Boolean {
    val version = GPT_MODEL_VERSION.find(model.trim().lowercase()) ?: return false
    val major = version.groupValues[1].toInt()
    val minor = version.groupValues[2].toInt()

    return major > 5 || major == 5 && minor >= 6
}

/**
 * Mark the two prefixes a turn re-sends, well inside the four cache writes a request may make: the
 * system instructions, stable for the life of the deployment, and the last user message, stable for
 * every iteration of one agent run — the tool loop re-sends the whole history on each of them.
 * OpenAI reads from the longest matching prefix, so the turn that grew the history still reads the
 * system one. Returns `null` when nothing could be marked, which keeps the caller from requesting
 * `explicit` mode with no breakpoint at all — that disables caching outright.
 */
private fun markCachedPrefixes(messages: JsonArray, textBlockType: String, markCurrentTurn: Boolean): JsonArray? {
    val systemIndex = messages.indexOfFirst { it.role == "developer" || it.role == "system" }
    val currentTurnIndex = if (markCurrentTurn) messages.indexOfLast { it.role == "user" } else -1

    val marked = messages.toMutableList()
    var markedAny = false

    for (index in setOf(systemIndex, currentTurnIndex).filter { it >= 0 }) {
        val message = marked[index] as? JsonObject ?: continue
        val content = markLastTextBlock(message["content"], textBlockType) ?: continue

        marked[index] = JsonObject(message + ("content" to content))
        markedAny = true
    }

    return if (markedAny) JsonArray(marked) else null
}

private val JsonElement.role: String?
    get() = ((this as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull

private fun markLastTextBlock(content: JsonElement?, textBlockType: String): JsonArray? {
    if (content is JsonPrimitive && content.isString) {
        return JsonArray(
            listOf(
                markedTextBlock(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive(textBlockType),
                            "text" to content
                        )
                    )
                )
            )
        )
    }

    val blocks = content as? JsonArray ?: return null
    val index =
        blocks.indexOfLast { block ->
            (((block as? JsonObject)?.get("type")) as? JsonPrimitive)?.contentOrNull == textBlockType
        }
    if (index < 0) return null

    val block = blocks[index] as? JsonObject ?: return null
    return JsonArray(
        blocks.mapIndexed { blockIndex, value ->
            if (blockIndex == index) markedTextBlock(block) else value
        }
    )
}

private fun markedTextBlock(block: JsonObject): JsonObject =
    JsonObject(block + ("prompt_cache_breakpoint" to EXPLICIT_CACHE_CONTROL))

private val GPT_MODEL_VERSION = Regex("""^gpt-(\d+)\.(\d+)""")
private val EXPLICIT_CACHE_CONTROL =
    buildJsonObject {
        put("mode", "explicit")
    }
