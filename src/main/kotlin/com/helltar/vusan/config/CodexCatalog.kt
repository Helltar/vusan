package com.helltar.vusan.config

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val log = KotlinLogging.logger {}

// what we tell the backend the calling Codex client is. reported from the locally installed CLI so the
// value stays truthful as it is upgraded; the constant is only a floor for hosts where the binary is
// absent but auth.json was copied in.
private const val CODEX_FALLBACK_CLIENT_VERSION = "0.146.1"
private val CODEX_VERSION = Regex("""\d+\.\d+\.\d+""")

private val detectedClientVersion: String by lazy {
    runCatching {
        val process = ProcessBuilder("codex", "--version").redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()

        CODEX_VERSION.find(output)?.value
    }.getOrNull() ?: CODEX_FALLBACK_CLIENT_VERSION
}

internal fun codexClientVersion(): String = detectedClientVersion

/** Whitelisted `codex_cli_rs/<version>` shape, with the real caller named in the trailing comment. */
internal fun codexUserAgent(): String = "$CODEX_ORIGINATOR/${codexClientVersion()} (Vusan)"

/** A model the signed-in ChatGPT account may actually run through Codex. */
data class CodexModel(
    val id: String,
    val displayName: String,
    val contextWindowTokens: Long?
)

@Serializable
private data class CodexModelsResponse(val models: List<CodexModelInfo> = emptyList())

@Serializable
private data class CodexModelInfo(
    val slug: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("context_window") val contextWindow: Long? = null,
    @SerialName("max_context_window") val maxContextWindow: Long? = null
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
            header("Authorization", "Bearer ${credentials.accessToken}")
            header("originator", CODEX_ORIGINATOR)
            header("User-Agent", codexUserAgent())
            credentials.accountId?.let { header("ChatGPT-Account-ID", it) }
        }.body()

    return response.models
        .filter { it.slug.isNotBlank() }
        .map {
            CodexModel(
                id = it.slug,
                displayName = it.displayName.ifBlank { it.slug },
                contextWindowTokens = it.contextWindow ?: it.maxContextWindow
            )
        }
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
