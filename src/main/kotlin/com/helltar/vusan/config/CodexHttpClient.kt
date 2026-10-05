package com.helltar.vusan.config

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.base.models.ServiceTier
import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

internal const val CODEX_BACKEND_BASE_URL = "https://chatgpt.com/backend-api/codex"

// Cloudflare fronts this endpoint and only lets a small set of first-party originators through
// (`codex_cli_rs`, `codex_vscode`, `codex_sdk_ts`, `Codex*`). A request from a non-residential IP — which
// is every VPS the bot would realistically run on — is answered with 403 and `cf-mitigated: challenge`
// when the originator is not one of them, no matter how good the token is. So claim the whitelisted
// value, and keep the honest name in the User-Agent comment rather than pretending outright.
internal const val CODEX_ORIGINATOR = "codex_cli_rs"

private const val CODEX_ROUTING_HINT_HEADER = "x-codex-routing-hint"
private const val CODEX_SESSION_ID_HEADER = "session-id"
private const val SSE_DATA_PREFIX = "data:"

private val log = KotlinLogging.logger {}

/**
 * What the CLI tells the backend to route a turn on: the model, plus the serving tier when one is asked
 * for. The tier is honoured without it today — it travels in the request body — but this is how Codex
 * itself asks, and on this endpoint matching the CLI is what keeps working.
 */
internal fun codexRoutingHint(model: String, serviceTier: ServiceTier?): String =
    "model=$model" + serviceTier?.let { ";tier=${it.requestValue}" }.orEmpty()

/**
 * The Koog HTTP factory Codex requests go through: a Ktor client that stamps a currently-valid ChatGPT
 * token on every outgoing request, wrapped so the backend's streaming-only contract stays invisible to
 * the rest of Koog.
 *
 * Auth is attached by a Ktor plugin rather than through Koog's per-request headers because those do not
 * survive to the wire on the raw-lines path this bridge depends on, and because Koog otherwise freezes
 * `Authorization` at construction from a `String` api key — which cannot work for a token that expires.
 */
internal fun codexHttpClientFactory(auth: CodexAuthStore, routingHint: String): KoogHttpClient.Factory =
    CodexHttpClientFactory(
        delegate = KtorKoogHttpClient.Factory(baseClient = HttpClient(CIO) { install(codexRequestPlugin(auth, routingHint)) }),
        limits = auth.limits,
    )

internal fun codexRequestPlugin(auth: CodexAuthStore, routingHint: String): ClientPlugin<Unit> =
    createClientPlugin("CodexAuth") {
        onRequest { request, content ->
            val credentials = auth.credentials()

            request.headers.remove(HttpHeaders.Authorization)
            request.headers.append(HttpHeaders.Authorization, "Bearer ${credentials.accessToken}")
            request.headers.append("originator", CODEX_ORIGINATOR)
            request.headers.append(CODEX_ROUTING_HINT_HEADER, routingHint)

            // the whitelist is matched on the User-Agent shape too, so lead with the
            // CLI token and installed version, then say who is really calling.
            request.headers.remove(HttpHeaders.UserAgent)
            request.headers.append(HttpHeaders.UserAgent, codexUserAgent())

            credentials.accountId?.let { request.headers.append("ChatGPT-Account-ID", it) }
            codexSessionId(content)?.let { request.headers.append(CODEX_SESSION_ID_HEADER, it) }
        }

        onResponse { response -> auth.limits.observe { response.headers[it] } }
    }

/**
 * The session a request is filed under, derived from the conversation's `prompt_cache_key`.
 *
 * On this backend the key alone reads almost nothing back: requests are spread over machines, and the
 * `session-id` header is what keeps a conversation on the one that holds its prefix. Probed live on
 * 2026-10-06 with a 6k-token prefix: five follow-up requests read 5888 cached tokens on four or five of
 * them with the header, and on none or one without it, whatever else was sent — `thread-id`,
 * `x-client-request-id` and a replayed `x-codex-turn-state` changed nothing on their own. The CLI sends a
 * UUID here, so the key is folded into one rather than sent as it is.
 */
internal fun codexSessionId(requestBody: Any): String? {
    if (requestBody !is String) return null

    val root = runCatching { Json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull()
    val cacheKey = (root?.get("prompt_cache_key") as? JsonPrimitive)?.contentOrNull

    return cacheKey?.let { UUID.nameUUIDFromBytes(it.toByteArray()).toString() }
}

private class CodexHttpClientFactory(
    private val delegate: KoogHttpClient.Factory,
    private val limits: CodexLimits,
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
                    json = json,
                ),
            json = json,
            limits = limits,
        )
}

private class CodexHttpClient(
    private val delegate: KoogHttpClient,
    private val json: Json,
    private val limits: CodexLimits,
) : KoogHttpClient {

    override val clientName: String = delegate.clientName

    private val lastReportedModel = AtomicReference<String?>(null)

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
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
        headers: Map<String, String>,
    ): R {
        if (requestBody !is String)
            return delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)

        val lines =
            delegate.lines(
                path = path,
                requestBody = forceStreamingRequest(requestBody, json),
                requestBodyType = String::class,
                parameters = parameters,
                headers = headers + mapOf("Accept" to "text/event-stream"),
            )

        val response = collectStreamedResponse(lines.toList(), json, clientName)
        countUsage(response, limits)
        warnOnAnotherModel(requestBody, response)
        val completed = response.toString()

        @Suppress("UNCHECKED_CAST")

        return json.decodeFromString(serializer(responseType.createType()), completed) as R
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
                    headers = headers,
                ),
            )
        }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> =
        flow {
            emitAll(
                delegate.lines(
                    path = path,
                    requestBody = streamingBodyOrOriginal(requestBody),
                    requestBodyType = requestBodyType,
                    parameters = parameters,
                    headers = headers,
                ),
            )
        }

    override fun close() = delegate.close()

    // the backend may answer from a model other than the one asked for, and says so only here. once per
    // model is enough: it is a fact about the deployment, not about a call.
    private fun warnOnAnotherModel(requestBody: String, response: JsonObject) {
        val served = servedModelMismatch(requestBody, response, json) ?: return

        if (lastReportedModel.getAndSet(served) != served) {
            log.warn { "codex answered from another model than the one requested: served=[$served]" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> streamingBodyOrOriginal(requestBody: T): T =
        if (requestBody is String) forceStreamingRequest(requestBody, json) as T else requestBody
}

/** The backend rejects any other combination, so set both rather than trusting the caller. */
internal fun forceStreamingRequest(requestBody: String, json: Json = Json): String {
    val root = runCatching { json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull() ?: return requestBody

    return JsonObject(
        root + mapOf("stream" to JsonPrimitive(true), "store" to JsonPrimitive(false)),
    ).toString()
}

/**
 * The model a completed response says it came from, when that is not the one the request named.
 *
 * A dated or suffixed name of the requested model is the same model, so only a different family counts.
 */
internal fun servedModelMismatch(requestBody: String, response: JsonObject, json: Json = Json): String? {
    val requested =
        runCatching { (json.parseToJsonElement(requestBody) as? JsonObject)?.get("model") }.getOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: return null

    val served = (response["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null

    return served.takeUnless { it.startsWith(requested, ignoreCase = true) }
}

/** Counts a completed response's tokens towards the subscription's next step, see [CodexLimits]. */
internal fun countUsage(response: JsonObject, limits: CodexLimits) {
    val usage = response["usage"] as? JsonObject ?: return

    fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: 0L

    limits.countCall(
        inputTokens = usage.long("input_tokens"),
        cachedInputTokens = (usage["input_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0L,
        outputTokens = usage.long("output_tokens"),
    )
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
            "response.failed", "response.incomplete", "response.cancelled", "error" ->
                throw KoogHttpClientException(clientName, 200, payload)
        }
    }

    val response =
        envelope
            ?: throw KoogHttpClientException(clientName, 200, "Codex stream ended without a completed response")

    val completedOutput =
        output.takeIf { it.isNotEmpty() }?.let(::JsonArray)
            ?: (response["output"] as? JsonArray)
            ?: JsonArray(emptyList())

    return JsonObject(response + mapOf("output" to completedOutput))
}
