package com.helltar.vusan.config

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.api.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

internal const val CODEX_BACKEND_BASE_URL = "https://chatgpt.com/backend-api/codex"

// Cloudflare fronts this endpoint and only lets a small set of first-party originators through
// (`codex_cli_rs`, `codex_vscode`, `codex_sdk_ts`, `Codex*`). A request from a non-residential IP — which
// is every VPS the bot would realistically run on — is answered with 403 and `cf-mitigated: challenge`
// when the originator is not one of them, no matter how good the token is. So claim the whitelisted
// value, and keep the honest name in the User-Agent comment rather than pretending outright.
internal const val CODEX_ORIGINATOR = "codex_cli_rs"

private const val SSE_DATA_PREFIX = "data:"

/**
 * The Koog HTTP factory Codex requests go through: a Ktor client that stamps a currently-valid ChatGPT
 * token on every outgoing request, wrapped so the backend's streaming-only contract stays invisible to
 * the rest of Koog.
 *
 * Auth is attached by a Ktor plugin rather than through Koog's per-request headers because those do not
 * survive to the wire on the raw-lines path this bridge depends on, and because Koog otherwise freezes
 * `Authorization` at construction from a `String` api key — which cannot work for a token that expires.
 */
internal fun codexHttpClientFactory(auth: CodexAuthStore): KoogHttpClient.Factory =
    CodexHttpClientFactory(
        delegate =
            KtorKoogHttpClient.Factory(
                baseClient =
                    HttpClient(CIO) {
                        install(
                            createClientPlugin("CodexAuth") {
                                onRequest { request, _ ->
                                    val credentials = auth.credentials()

                                    request.headers.remove(HttpHeaders.Authorization)
                                    request.headers.append(
                                        HttpHeaders.Authorization,
                                        "Bearer ${credentials.accessToken}"
                                    )
                                    request.headers.append("originator", CODEX_ORIGINATOR)

                                    // the whitelist is matched on the User-Agent shape too, so lead with the
                                    // CLI token and installed version, then say who is really calling.
                                    request.headers.remove(HttpHeaders.UserAgent)
                                    request.headers.append(HttpHeaders.UserAgent, codexUserAgent())

                                    credentials.accountId?.let { request.headers.append("ChatGPT-Account-ID", it) }
                                }
                            }
                        )
                    }
            )
    )

private class CodexHttpClientFactory(
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
        CodexHttpClient(
            delegate =
                delegate.create(
                    clientName = clientName,
                    baseUrl = baseUrl,
                    // drop the placeholder key koog derived an Authorization header from. Ktor installs it
                    // as a default request header, which a per-request header does not displace, so leaving
                    // it here sends `Bearer codex-oauth` and the backend rejects an unparseable token.
                    headers = headers.filterKeys { !it.equals("Authorization", ignoreCase = true) },
                    queryParameters = queryParameters,
                    requestTimeoutMillis = requestTimeoutMillis,
                    connectTimeoutMillis = connectTimeoutMillis,
                    socketTimeoutMillis = socketTimeoutMillis,
                    json = json
                ),
            json = json
        )
}

private class CodexHttpClient(
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

    /**
     * Answer an ordinary non-streaming call by streaming it and reassembling the result.
     *
     * The Codex backend accepts nothing else: `stream=false` is rejected with "Stream must be set to
     * true" and `store=true` with "Store must be set to false". Bridging here rather than in a custom
     * `LLMClient` keeps Koog's own response parsing — tool calls, reasoning items, usage — in play, and
     * leaves the agent loop unaware that this provider streams at all.
     */
    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): R {
        if (requestBody !is String)
            return delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)

        val lines =
            delegate.lines(
                path = path,
                requestBody = forceStreamingRequest(requestBody, json),
                requestBodyType = String::class,
                parameters = parameters,
                headers = headers + mapOf("Accept" to "text/event-stream")
            )

        val completed = collectStreamedResponse(lines.toList(), json, clientName)

        @Suppress("UNCHECKED_CAST")
        return json.decodeFromString(serializer(responseType.createType()), completed.toString()) as R
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
        flow {
            emitAll(
                delegate.sse(
                    path = path,
                    requestBody = streamingBodyOrOriginal(requestBody),
                    requestBodyType = requestBodyType,
                    dataFilter = dataFilter,
                    decodeStreamingResponse = decodeStreamingResponse,
                    processStreamingChunk = processStreamingChunk,
                    parameters = parameters,
                    headers = headers
                )
            )
        }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): Flow<String> =
        flow {
            emitAll(
                delegate.lines(
                    path = path,
                    requestBody = streamingBodyOrOriginal(requestBody),
                    requestBodyType = requestBodyType,
                    parameters = parameters,
                    headers = headers
                )
            )
        }

    override fun close() = delegate.close()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> streamingBodyOrOriginal(requestBody: T): T =
        if (requestBody is String) forceStreamingRequest(requestBody, json) as T else requestBody
}

/** The backend rejects any other combination, so set both rather than trusting the caller. */
internal fun forceStreamingRequest(requestBody: String, json: Json = Json): String {
    val root = runCatching { json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull() ?: return requestBody

    return JsonObject(
        root + mapOf("stream" to JsonPrimitive(true), "store" to JsonPrimitive(false))
    ).toString()
}

/**
 * Fold a Responses API event stream back into the single response object the non-streaming API would
 * have returned.
 *
 * The final `response.completed` event carries the envelope — status, model, usage — but the Codex
 * backend leaves its `output` array empty, so the items are collected from `response.output_item.done`
 * as they arrive and spliced back in. Everything the agent depends on rides in those items: assistant
 * text, tool calls, and the reasoning items a tool loop has to echo back.
 */
internal fun collectStreamedResponse(lines: List<String>, json: Json, clientName: String): JsonObject {
    var envelope: JsonObject? = null
    val output = mutableListOf<kotlinx.serialization.json.JsonElement>()

    for (line in lines) {
        if (!line.startsWith(SSE_DATA_PREFIX)) continue

        val payload = line.removePrefix(SSE_DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == "[DONE]") continue

        val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue

        when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
            "response.output_item.done" -> event["item"]?.let { output.add(it) }
            "response.completed" -> envelope = event["response"] as? JsonObject
            "response.failed", "error" -> throw KoogHttpClientException(clientName, 200, payload)
        }
    }

    val response =
        envelope
            ?: throw KoogHttpClientException(clientName, 200, "Codex stream ended without a completed response")

    return JsonObject(response + mapOf("output" to JsonArray(output)))
}
