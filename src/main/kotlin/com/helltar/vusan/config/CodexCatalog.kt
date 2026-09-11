package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.base.models.ServiceTier
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

// the Codex client version we claim to the backend. `/models` is filtered by it: a model whose
// `minimal_client_version` is newer is simply absent from the catalog, so an understated value makes
// the newest model look like one the plan does not offer. a locally installed CLI wins while it is
// newer, so a host that upgrades codex sees new models without a vusan release; the constant is the
// floor everywhere else, the container included, where there is no binary to ask.
private const val CODEX_CLIENT_VERSION_FLOOR = "0.153.4"
internal val CODEX_VERSION = Regex("""\d+\.\d+\.\d+""")

@Volatile
private var pinnedClientVersion: String? = null

private val reportedClientVersion: String by lazy {
    detectCodexClientVersion()?.takeIf { it isNewerThan CODEX_CLIENT_VERSION_FLOOR } ?: CODEX_CLIENT_VERSION_FLOOR
}

/**
 * Claim [version] instead of the built-in floor, from `CODEX_CLIENT_VERSION`.
 *
 * The escape hatch for a model whose `minimal_client_version` is newer than any version this build knows
 * of: until one is claimed the catalog simply omits that model and startup rejects it as one the plan
 * does not offer. Installed once at startup rather than passed per call, because the process has a
 * single Codex identity and a parameter would only let one caller claim a different one.
 */
internal fun pinCodexClientVersion(version: String?) {
    pinnedClientVersion = version
}

internal fun detectCodexClientVersion(
    command: List<String> = listOf("codex", "--version"),
    timeout: Duration = 5.seconds
): String? =
    runCatching {
        require(command.isNotEmpty()) { "Codex version command must not be empty" }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()

        try {
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return@runCatching null
            }

            CODEX_VERSION.find(process.inputStream.bufferedReader().use { it.readText() })?.value
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }.getOrNull()

internal fun codexClientVersion(): String = pinnedClientVersion ?: reportedClientVersion

/** Numeric `major.minor.patch` ordering, so `0.99.0` does not outrank `0.146.1` the way strings do. */
internal infix fun String.isNewerThan(other: String): Boolean {
    val mine = versionParts()
    val theirs = other.versionParts()

    return mine.zip(theirs).firstOrNull { (left, right) -> left != right }?.let { (left, right) -> left > right }
        ?: (mine.size > theirs.size)
}

private fun String.versionParts(): List<Int> = split('.').map { it.toIntOrNull() ?: 0 }

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
        "User-Agent" to codexUserAgent()
    )

/** Everything a plain HTTP call to the Codex backend needs: the token plus the Cloudflare headers. */
fun codexImageHeaders(credentials: CodexCredentials): Map<String, String> =
    buildMap {
        put("Authorization", "Bearer ${credentials.accessToken}")
        putAll(codexCloudflareHeaders())
        credentials.accountId?.let { put("ChatGPT-Account-ID", it) }
    }

/** The value a tier travels as, which is also the id the model catalog lists it under. */
internal val ServiceTier.requestValue: String
    get() = name.lowercase()

/** A model the signed-in ChatGPT account may actually run through Codex. */
data class CodexModel(
    val id: String,
    val displayName: String,
    val contextWindowTokens: Long?,
    val supportsVision: Boolean,
    val supportedReasoningEfforts: Set<ReasoningEffort>?,
    // the serving tiers this model offers beyond the standard one, by their request value. an empty set
    // means the catalog says none; `null` means it did not say.
    val supportedServiceTiers: Set<String>?
)

@Serializable
private data class CodexModelsResponse(val models: List<CodexModelInfo> = emptyList())

@Serializable
private data class CodexModelInfo(
    val slug: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("context_window") val contextWindow: Long? = null,
    @SerialName("max_context_window") val maxContextWindow: Long? = null,
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
    @SerialName("supported_reasoning_efforts") val supportedReasoningEfforts: JsonElement? = null,
    @SerialName("supported_reasoning_levels") val supportedReasoningLevels: JsonElement? = null,
    @SerialName("service_tiers") val serviceTiers: List<CodexServiceTierInfo>? = null
)

@Serializable
private data class CodexServiceTierInfo(val id: String = "")

/**
 * The model catalog the signed-in subscription is entitled to.
 *
 * This is deliberately not the OpenAI Platform model list: the two overlap but are not the same set,
 * and picking a Platform-only model here fails at the first turn with an opaque backend error. Asking
 * the account what it can run is the only way to reject that at startup instead.
 */
suspend fun fetchCodexModels(http: HttpClient, auth: CodexAuthStore): List<CodexModel> {
    val credentials = auth.credentials()

    val response: CodexModelsResponse =
        http.get("$CODEX_BACKEND_BASE_URL/models") {
            // the endpoint 400s without it: "query.client_version: Field required"
            parameter("client_version", codexClientVersion())
            codexImageHeaders(credentials).forEach { (name, value) -> header(name, value) }
        }.body()

    return response.models
        .filter { it.slug.isNotBlank() }
        .map {
            CodexModel(
                id = it.slug,
                displayName = it.displayName.ifBlank { it.slug },
                contextWindowTokens = it.contextWindow ?: it.maxContextWindow,
                // older catalogs omit modalities; those models predate this metadata and accepted images.
                supportsVision = it.inputModalities?.any { modality -> modality.equals("image", true) } ?: true,
                supportedReasoningEfforts =
                    (it.supportedReasoningEfforts ?: it.supportedReasoningLevels).reasoningEfforts(),
                supportedServiceTiers =
                    it.serviceTiers?.mapNotNull { tier -> tier.id.takeIf(String::isNotBlank) }?.toSet()
            )
        }
}

private fun JsonElement?.reasoningEfforts(): Set<ReasoningEffort>? {
    val values = this as? JsonArray ?: return null
    val efforts =
        values.mapNotNull { value ->
            val raw =
                when (value) {
                    is JsonPrimitive -> value.contentOrNull
                    is JsonObject ->
                        listOf("reasoning_effort", "reasoningEffort", "effort", "level")
                            .firstNotNullOfOrNull { key -> value[key]?.jsonPrimitive?.contentOrNull }
                    else -> null
                }

            raw?.let { runCatching { ReasoningEffort.valueOf(it.trim().uppercase()) }.getOrNull() }
        }.toSet()

    // an unfamiliar non-empty shape is metadata drift, so skip validation instead of rejecting a
    // working model. an explicit empty array still means that no selectable effort is supported.
    return efforts.takeIf { it.isNotEmpty() || values.isEmpty() }
}

internal fun applyCodexModelMetadata(
    config: LlmProviderConfig.Codex,
    model: CodexModel
): LlmProviderConfig.Codex {
    val configuredEffort = config.reasoningEffort
    val supportedEfforts = model.supportedReasoningEfforts

    if (configuredEffort != null && supportedEfforts != null) {
        require(configuredEffort in supportedEfforts) {
            "LLM_REASONING_EFFORT=[${configuredEffort.requestValue}] is not supported by " +
                    "LLM_MODEL=[${model.id}]. Supported values: " +
                    supportedEfforts.sorted().joinToString { it.requestValue }
        }
    }

    val configuredTier = config.serviceTier
    val supportedTiers = model.supportedServiceTiers

    if (configuredTier != null && supportedTiers != null) {
        require(configuredTier.requestValue in supportedTiers) {
            "CODEX_SERVICE_TIER=[${configuredTier.requestValue}] is not supported by " +
                    "LLM_MODEL=[${model.id}]. Supported values: " +
                    supportedTiers.sorted().joinToString().ifEmpty { "none" }
        }
    }

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: model.contextWindowTokens,
        supportsVision = model.supportsVision
    )
}

/**
 * Check the configured model against the account's catalog before the first turn.
 *
 * Returns the catalog entry when it matches, `null` when the catalog could not be read. A missing
 * catalog is not fatal: `/models` is an undocumented endpoint, and a shape change there must not take
 * a working bot down — a wrong model name still surfaces on the first turn. A model the account
 * plainly cannot run *is* fatal, because that is the confusing failure worth catching early.
 */
suspend fun verifyCodexModel(http: HttpClient, auth: CodexAuthStore, model: String): CodexModel? {
    val catalog =
        runCatching { fetchCodexModels(http, auth) }
            .getOrElse { e ->
                if (e is CodexAuthException) throw e

                log.warn { "Codex: could not read the model catalog (${e.message}); skipping the model check" }
                return null
            }

    if (catalog.isEmpty()) {
        log.warn { "Codex: the model catalog came back empty; skipping the model check" }
        return null
    }

    val match = catalog.firstOrNull { it.id.equals(model.trim(), ignoreCase = true) }

    checkNotNull(match) {
        // the catalog is filtered by the version we claim, so a model too new for it is missing rather
        // than refused — worth naming here, since the list alone reads as an entitlement problem.
        "LLM_MODEL=[$model] is not available on this ChatGPT subscription. " +
                "Available models: ${catalog.map { it.id }.sorted().joinToString()}. " +
                "Models newer than client_version=[${codexClientVersion()}] are hidden from that list."
    }

    return match
}
