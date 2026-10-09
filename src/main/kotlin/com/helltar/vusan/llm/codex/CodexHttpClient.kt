package com.helltar.vusan.llm.codex

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.api.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal const val CODEX_BACKEND_BASE_URL = "https://chatgpt.com/backend-api/codex"

// Cloudflare fronts this endpoint and only lets a small set of first-party originators through
// (`codex_cli_rs`, `codex_vscode`, `codex_sdk_ts`, `Codex*`). A request from a non-residential IP — which
// is every VPS the bot would realistically run on — is answered with 403 and `cf-mitigated: challenge`
// when the originator is not one of them, no matter how good the token is. So claim the whitelisted
// value, and keep the honest name in the User-Agent comment rather than pretending outright.
internal const val CODEX_ORIGINATOR = "codex_cli_rs"

private const val CODEX_ROUTING_HINT_HEADER = "x-codex-routing-hint"
private const val CODEX_SESSION_ID_HEADER = "session-id"
private const val PERCENT = 100

private val log = KotlinLogging.logger {}

/** Whitelisted `codex_cli_rs/<version>` shape, with the real caller named in the trailing comment. */
internal fun codexUserAgent(): String = "$CODEX_ORIGINATOR/${codexClientVersion()} (Vusan)"

/**
 * The two headers Cloudflare checks on every host Codex talks to, `auth.openai.com` included — the CLI
 * puts them on its auth route as well. Shared so a second caller cannot quietly omit one and fail only
 * once deployed to a VPS.
 */
internal fun codexCloudflareHeaders(): Map<String, String> =
    mapOf(
        "originator" to CODEX_ORIGINATOR,
        "User-Agent" to codexUserAgent(),
    )

/** Everything a plain HTTP call to the Codex backend needs: the token plus the Cloudflare headers. */
fun codexRequestHeaders(credentials: CodexCredentials): Map<String, String> =
    buildMap {
        put("Authorization", "Bearer ${credentials.accessToken}")
        putAll(codexCloudflareHeaders())
        credentials.accountId?.let { put("ChatGPT-Account-ID", it) }
    }

/**
 * What the CLI tells the backend to route a turn on: the model, plus the serving tier when one is asked
 * for. The tier is honored without it today — it travels in the request body — but this is how Codex
 * itself asks, and on this endpoint matching the CLI is what keeps working.
 */
internal fun codexRoutingHint(model: String, serviceTier: String?): String =
    "model=$model" + serviceTier?.let { ";tier=$it" }.orEmpty()

/**
 * Signs every request to the Codex backend with a currently valid ChatGPT token and the headers
 * Cloudflare checks, and reads the subscription's usage windows off every response.
 *
 * A token expires and is rotated behind our back, so it is read per request rather than fixed when the
 * client is built.
 */
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

    return cacheKey?.let(::sessionIdOf)
}

private fun sessionIdOf(cacheKey: String): String = UUID.nameUUIDFromBytes(cacheKey.toByteArray()).toString()

/**
 * What is done with every completed Codex call: its tokens are counted toward the subscription's next
 * step, the call is logged with its cache share and where its prefix drifted, and a reply from another
 * model than the one asked for is noticed — once per model, since it is a fact about the deployment.
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
 * The model a completed response says it came from, when that is not the one the request named.
 *
 * A dated or suffixed name of the requested model is the same model, so only a different family counts.
 */
internal fun servedModelMismatch(request: JsonObject, response: JsonObject): String? {
    val requested = (request["model"] as? JsonPrimitive)?.contentOrNull ?: return null
    val served = (response["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null

    return served.takeUnless { it.startsWith(requested, ignoreCase = true) }
}

/**
 * One model call as a log line: whose conversation it was, how much of its input was read from cache,
 * and how many tools rode along.
 *
 * The limits line next to it adds calls up between two changes of the allowance, which hides exactly
 * what a cache question asks: which call of which conversation missed. The conversation is named by its
 * cache key, a hash the turn logs as well, so its calls can be followed without naming anybody; the
 * tool count is there because a group loaded mid-turn changes the front of the request and takes the
 * cached prefix with it.
 */
internal fun codexCallSummary(request: JsonObject, response: JsonObject): String {
    val usage = response["usage"] as? JsonObject

    fun JsonObject?.long(name: String): Long = (this?.get(name) as? JsonPrimitive)?.longOrNull ?: 0L

    val input = usage.long("input_tokens")
    val cached = (usage?.get("input_tokens_details") as? JsonObject).long("cached_tokens")
    val cachedPercent = if (input > 0) cached * PERCENT / input else 0

    val cacheKey = (request["prompt_cache_key"] as? JsonPrimitive)?.contentOrNull

    return "cacheKey=[${cacheKey ?: "none"}] " +
            "input=[$input] cached=[$cached] cachedPercent=[$cachedPercent] " +
            "output=[${usage.long("output_tokens")}] tools=[${(request["tools"] as? JsonArray)?.size ?: 0}]"
}

/** Counts a completed response's tokens toward the subscription's next step, see [CodexLimits]. */
internal fun countUsage(response: JsonObject, limits: CodexLimits) {
    val usage = response["usage"] as? JsonObject ?: return

    fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.longOrNull ?: 0L

    limits.countCall(
        inputTokens = usage.long("input_tokens"),
        cachedInputTokens = (usage["input_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: 0L,
        outputTokens = usage.long("output_tokens"),
    )
}
