package com.helltar.vusan.llm.codex

import com.helltar.vusan.llm.long
import com.helltar.vusan.llm.PROMPT_CACHE_KEY
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.api.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal const val CODEX_BACKEND_BASE_URL = "https://chatgpt.com/backend-api/codex"

// cloudflare in front of this endpoint lets only first-party originators (`codex_cli_rs`, `codex_vscode`,
// `codex_sdk_ts`, `Codex*`) through from non-residential IPs, which means every VPS: anything else gets a 403
// with `cf-mitigated: challenge` whatever the token. so claim the whitelisted value and name the real caller
// in the User-Agent comment.
internal const val CODEX_ORIGINATOR = "codex_cli_rs"

private const val CODEX_ROUTING_HINT_HEADER = "x-codex-routing-hint"
private const val CODEX_SESSION_ID_HEADER = "session-id"
private const val PERCENT = 100

private val log = KotlinLogging.logger {}

/** The whitelisted `codex_cli_rs/<version>` shape, the real caller named in the trailing comment. */
internal fun codexUserAgent(): String = "$CODEX_ORIGINATOR/${codexClientVersion()} (Vusan)"

/**
 * The two headers cloudflare checks on every Codex host, `auth.openai.com` included. Shared so no caller can
 * omit one and fail only once deployed to a VPS.
 */
internal fun codexCloudflareHeaders(): Map<String, String> =
    mapOf(
        "originator" to CODEX_ORIGINATOR,
        "User-Agent" to codexUserAgent(),
    )

/** Everything a plain HTTP call to the Codex backend needs: the token plus the cloudflare headers. */
fun codexRequestHeaders(credentials: CodexCredentials): Map<String, String> =
    buildMap {
        put("Authorization", "Bearer ${credentials.accessToken}")
        putAll(codexCloudflareHeaders())
        credentials.accountId?.let { put("ChatGPT-Account-ID", it) }
    }

/**
 * What the CLI routes a turn on: the model, plus the serving tier when asked. The tier works without it today
 * (it is in the body), but matching the CLI is what keeps working on this endpoint.
 */
internal fun codexRoutingHint(model: String, serviceTier: String?): String =
    "model=$model" + serviceTier?.let { ";tier=$it" }.orEmpty()

/**
 * Signs every Codex request with a valid ChatGPT token and the cloudflare headers, and reads the usage
 * windows off every response. The token rotates behind our back, so it is read per request.
 */
internal fun codexRequestPlugin(auth: CodexAuthStore, routingHint: String): ClientPlugin<Unit> =
    createClientPlugin("CodexAuth") {
        onRequest { request, _ ->
            val credentials = auth.credentials()

            // replace the client's defaults for these two rather than adding to them
            request.headers.remove(HttpHeaders.Authorization)
            request.headers.remove(HttpHeaders.UserAgent)
            codexRequestHeaders(credentials).forEach { (name, value) -> request.headers.append(name, value) }
            request.headers.append(CODEX_ROUTING_HINT_HEADER, routingHint)
            request.attributes.getOrNull(PROMPT_CACHE_KEY)?.let { request.headers.append(CODEX_SESSION_ID_HEADER, sessionIdOf(it)) }
        }

        onResponse { response -> auth.limits.observe { response.headers[it] } }
    }

/**
 * The session a request is filed under, derived from the `prompt_cache_key` the request carries as an
 * attribute, so nothing here reads the body back.
 *
 * The key alone reads almost nothing back here: requests are spread over machines, and `session-id` keeps a
 * conversation on the one holding its prefix. Probed 2026-10-06 with a 6k-token prefix: five follow-ups read
 * 5888 cached tokens on four or five of them with the header, on none or one without; `thread-id`,
 * `x-client-request-id` and a replayed `x-codex-turn-state` changed nothing alone. The CLI sends a UUID, so
 * the key is folded into one.
 */
internal fun sessionIdOf(cacheKey: String): String = UUID.nameUUIDFromBytes(cacheKey.toByteArray()).toString()

/**
 * Per completed call: counts its tokens toward the subscription's next step, logs it with its cache share and
 * prefix drift, and notices a reply from another model than requested, once per model.
 */
internal fun codexCallObserver(limits: CodexLimits): (request: JsonObject, response: JsonObject) -> Unit {
    val drift = CodexPrefixDrift()
    val lastReportedModel = AtomicReference<String?>(null)

    return { request, response ->
        countUsage(response, limits)

        val cacheKey = (request["prompt_cache_key"] as? JsonPrimitive)?.contentOrNull
        val driftNote = cacheKey?.let { " " + drift.describe(it, request) }.orEmpty()

        log.info { "codex call: ${codexCallSummary(request, response)}$driftNote" }

        servedModelMismatch(request, response)?.let { served ->
            if (lastReportedModel.getAndSet(served) != served) {
                log.warn { "codex answered from another model than the one requested: served=[$served]" }
            }
        }
    }
}

/**
 * The model a response says it came from, when that differs from the requested one. A dated or suffixed name
 * is the same model; only a different family counts.
 */
internal fun servedModelMismatch(request: JsonObject, response: JsonObject): String? {
    val requested = (request["model"] as? JsonPrimitive)?.contentOrNull ?: return null
    val served = (response["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null

    return served.takeUnless { it.startsWith(requested, ignoreCase = true) }
}

/**
 * One model call as a log line: the conversation, the cached share of its input, and the tool count.
 *
 * The limits line sums calls between allowance changes, which hides which call missed the cache. The
 * conversation is named by its cache key, a hash the turn logs too, so its calls can be followed anonymously;
 * the tool count matters because a group loaded mid-turn changes the front of the request and loses the
 * cached prefix.
 */
internal fun codexCallSummary(request: JsonObject, response: JsonObject): String {
    val usage = response["usage"] as? JsonObject

    val input = usage?.long("input_tokens") ?: 0L
    val cached = (usage?.get("input_tokens_details") as? JsonObject)?.long("cached_tokens") ?: 0L
    val cachedPercent = if (input > 0) cached * PERCENT / input else 0

    val cacheKey = (request["prompt_cache_key"] as? JsonPrimitive)?.contentOrNull

    return "cacheKey=[${cacheKey ?: "none"}] " +
            "input=[$input] cached=[$cached] cachedPercent=[$cachedPercent] " +
            "output=[${usage?.long("output_tokens") ?: 0L}] tools=[${(request["tools"] as? JsonArray)?.size ?: 0}]"
}

/** Counts a response's tokens toward the subscription's next step; see [CodexLimits]. */
internal fun countUsage(response: JsonObject, limits: CodexLimits) {
    val usage = response["usage"] as? JsonObject ?: return

    limits.countCall(
        inputTokens = usage.long("input_tokens") ?: 0L,
        cachedInputTokens = (usage["input_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0L,
        outputTokens = usage.long("output_tokens") ?: 0L,
    )
}
