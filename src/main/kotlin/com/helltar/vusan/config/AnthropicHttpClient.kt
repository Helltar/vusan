package com.helltar.vusan.config

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass

// lets a request say what to do with a thinking block whose conversation changed under it, which the
// api otherwise refuses for accounts created after 2026-08-31. harmless on a model that never binds one.
private const val ANTHROPIC_BETA_HEADER = "anthropic-beta"
private const val THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01"

/**
 * The transport every Anthropic client here goes through: the beta header the thinking `block_binding`
 * field needs, and a second cache breakpoint on the system prompt.
 *
 * Koog 1.3.0 sets the headers once, at construction, and can place a `cache_control` only at the request
 * top level or on a message part the prompt was built with; the prompt here is built the same for every
 * provider, so the system block is marked on the wire instead. Delete the rewrite once koog can mark it
 * from provider-neutral code, and the header once `AnthropicThinking` carries `block_binding`.
 */
internal class AnthropicHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
) : KoogHttpClient.Factory {

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient =
        AnthropicHttpClient(
            delegate =
                delegate.create(
                    clientName = clientName,
                    baseUrl = baseUrl,
                    headers = headers + (ANTHROPIC_BETA_HEADER to THINKING_BINDING_CONTROLS_BETA),
                    queryParameters = queryParameters,
                    requestTimeoutMillis = requestTimeoutMillis,
                    connectTimeoutMillis = connectTimeoutMillis,
                    socketTimeoutMillis = socketTimeoutMillis,
                    json = json,
                ),
            json = json,
        )
}

private class AnthropicHttpClient(
    private val delegate: KoogHttpClient,
    private val json: Json,
) : KoogHttpClient {

    override val clientName: String = delegate.clientName

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.get(path, responseType, parameters, headers)

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R =
        if (requestBody is String) {
            delegate.post(
                path = path,
                requestBody = addAnthropicSystemCacheBreakpoint(requestBody, json),
                requestBodyType = String::class,
                responseType = responseType,
                parameters = parameters,
                headers = headers,
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
        headers: Map<String, String>,
    ): Flow<O> =
        if (requestBody is String) {
            delegate.sse(
                path = path,
                requestBody = addAnthropicSystemCacheBreakpoint(requestBody, json),
                requestBodyType = String::class,
                dataFilter = dataFilter,
                decodeStreamingResponse = decodeStreamingResponse,
                processStreamingChunk = processStreamingChunk,
                parameters = parameters,
                headers = headers,
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
                headers,
            )
        }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> =
        if (requestBody is String) {
            delegate.lines(
                path = path,
                requestBody = addAnthropicSystemCacheBreakpoint(requestBody, json),
                requestBodyType = String::class,
                parameters = parameters,
                headers = headers,
            )
        } else {
            delegate.lines(path, requestBody, requestBodyType, parameters, headers)
        }

    override fun close() = delegate.close()
}

/**
 * Gives a request that asks for automatic caching a second breakpoint, on its last system block.
 *
 * The automatic one lands on the last block of the request, which an agent loop re-reads on every
 * iteration — but the next turn replays its history from storage, in another shape than the one the
 * live turn sent, so the prefix diverges before anything that breakpoint ever covered. The system block
 * is the prefix every request of the deployment shares; a breakpoint there is what the next turn still
 * reads, tool schemas included, since tools render ahead of it. Same TTL as the automatic one, or the
 * API refuses the pair. A request that asks for no caching, the history recap, is left alone.
 */
internal fun addAnthropicSystemCacheBreakpoint(requestBody: String, json: Json = Json): String {
    val root = json.parseToJsonElement(requestBody) as? JsonObject ?: return requestBody
    val cacheControl = root["cache_control"] as? JsonObject ?: return requestBody
    val system = root["system"] as? JsonArray ?: return requestBody
    val last = system.lastOrNull() as? JsonObject ?: return requestBody
    if (last.containsKey("cache_control")) return requestBody

    val marked = JsonArray(system.dropLast(1) + JsonObject(last + ("cache_control" to cacheControl)))

    return JsonObject(root + ("system" to marked)).toString()
}
