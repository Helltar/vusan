package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
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

// what we tell the backend the calling Codex client is. reported from the locally installed CLI so the
// value stays truthful as it is upgraded; the constant is only a floor for hosts where the binary is
// absent but auth.json was copied in.
private const val CODEX_FALLBACK_CLIENT_VERSION = "0.146.1"
private val CODEX_VERSION = Regex("""\d+\.\d+\.\d+""")

private val detectedClientVersion: String by lazy {
    detectCodexClientVersion() ?: CODEX_FALLBACK_CLIENT_VERSION
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

internal fun codexClientVersion(): String = detectedClientVersion

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

/** A model the signed-in ChatGPT account may actually run through Codex. */
data class CodexModel(
    val id: String,
    val displayName: String,
    val contextWindowTokens: Long?,
    val supportsVision: Boolean,
    val supportedReasoningEfforts: Set<ReasoningEffort>?
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
    @SerialName("supported_reasoning_levels") val supportedReasoningLevels: JsonElement? = null
)

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
                    (it.supportedReasoningEfforts ?: it.supportedReasoningLevels).reasoningEfforts()
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
            "LLM_REASONING_EFFORT=[${configuredEffort.name.lowercase()}] is not supported by " +
                    "LLM_MODEL=[${model.id}]. Supported values: " +
                    supportedEfforts.map { it.name.lowercase() }.sorted().joinToString()
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
        "LLM_MODEL=[$model] is not available on this ChatGPT subscription. " +
                "Available models: ${catalog.map { it.id }.sorted().joinToString()}"
    }

    return match
}
