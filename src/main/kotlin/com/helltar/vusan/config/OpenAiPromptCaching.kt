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

// koog 1.1.1 exposes prompt_cache_key but not gpt-5.6 cache breakpoints, so rewrite only official
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

    val markedMessages =
        (root["input"] as? JsonArray)
            ?.let { markStableSystemPrefix(it, "input_text") }
            ?.let { "input" to it }
            ?: (root["messages"] as? JsonArray)
                ?.let { markStableSystemPrefix(it, "text") }
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

private fun markStableSystemPrefix(messages: JsonArray, textBlockType: String): JsonArray? {
    val index =
        messages.indexOfFirst { message ->
            val role = ((message as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull
            role == "developer" || role == "system"
        }
    if (index < 0) return null

    val message = messages[index] as? JsonObject ?: return null
    val markedContent = markLastTextBlock(message["content"], textBlockType) ?: return null
    val markedMessage = JsonObject(message + ("content" to markedContent))

    return JsonArray(messages.mapIndexed { messageIndex, value -> if (messageIndex == index) markedMessage else value })
}

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
