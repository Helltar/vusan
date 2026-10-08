package com.helltar.vusan.llm.codex

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
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
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.ReasoningEffort

private val log = KotlinLogging.logger {}

// the Codex client version we claim to the backend. `/models` is filtered by it: a model whose
// `minimal_client_version` is newer is simply absent from the catalog, so an understated value makes
// the newest model look like one the plan does not offer. startup claims the newest version it can
// find — this floor, the installed CLI, the latest release on github — so a host that upgrades codex
// and a container with no binary at all both see new models without a vusan release; the constant
// decides only where github is out of reach.
private const val CODEX_CLIENT_VERSION_FLOOR = "0.162.0"
// where the CLI's own update check looks: the latest non-prerelease, tagged `rust-v<version>`
private const val CODEX_LATEST_RELEASE_URL = "https://api.github.com/repos/openai/codex/releases/latest"
private val CODEX_LATEST_RELEASE_TIMEOUT = 5.seconds
internal val CODEX_VERSION = Regex("""\d+\.\d+\.\d+""")

@Volatile
private var claimedClientVersion: String? = null

/**
 * Decide the client version this process claims, once at startup, and log where it came from.
 *
 * [pinned] is `CODEX_CLIENT_VERSION` and wins outright: the lever for a host that cannot reach GitHub,
 * or for when the automatic choice is wrong. Otherwise the newest of the built-in floor, the installed
 * CLI and the latest GitHub release wins, and a source that did not answer is skipped. Installed once
 * rather than passed per call, because the process has a single Codex identity and a parameter would
 * only let one caller claim a different one.
 */
suspend fun claimCodexClientVersion(
    http: HttpClient,
    pinned: String?,
    installed: () -> String? = { detectCodexClientVersion() },
) {
    if (pinned != null) {
        claimedClientVersion = pinned
        log.info { "Codex: client_version=[$pinned] pinned by CODEX_CLIENT_VERSION" }
        return
    }

    val candidates =
        listOf(
            "built-in floor" to CODEX_CLIENT_VERSION_FLOOR,
            "installed CLI" to installed(),
            "latest GitHub release" to fetchLatestCodexRelease(http),
        )

    // on a tie the earlier source keeps the credit, so the floor is named whenever it is already current
    val (source, version) =
        candidates
            .mapNotNull { (name, found) -> found?.let { name to it } }
            .reduce { best, next -> if (next.second isNewerThan best.second) next else best }

    claimedClientVersion = version
    log.info {
        val others =
            candidates
                .filter { (name, _) -> name != source }
                .joinToString { (name, found) -> "$name=[${found ?: "none"}]" }

        "Codex: client_version=[$version] from the $source; $others"
    }
}

/**
 * The version of the latest Codex release on GitHub, or `null` when it could not be read: the fetch is
 * best effort, and the other sources stand in for it.
 */
internal suspend fun fetchLatestCodexRelease(http: HttpClient): String? =
    runCatching {
        val release: GitHubRelease =
            http.get(CODEX_LATEST_RELEASE_URL) {
                timeout { requestTimeoutMillis = CODEX_LATEST_RELEASE_TIMEOUT.inWholeMilliseconds }
                header("User-Agent", codexUserAgent())
            }.body()

        requireNotNull(CODEX_VERSION.find(release.tagName)?.value) { "no version in tag_name=[${release.tagName}]" }
    }.onFailure { e ->
        e.rethrowIfCancellation()
        log.warn { "Codex: could not read the latest release from GitHub (${e.message}); claiming the client version without it" }
    }.getOrNull()

internal fun detectCodexClientVersion(
    command: List<String> = listOf("codex", "--version"),
    timeout: Duration = 5.seconds,
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

internal fun codexClientVersion(): String = claimedClientVersion ?: CODEX_CLIENT_VERSION_FLOOR

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
        "User-Agent" to codexUserAgent(),
    )

/** Everything a plain HTTP call to the Codex backend needs: the token plus the Cloudflare headers. */
fun codexRequestHeaders(credentials: CodexCredentials): Map<String, String> =
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
    val supportedReasoningEfforts: Set<ReasoningEffort>?,
    // the serving tiers this model offers beyond the standard one, by their request value. an empty set
    // means the catalog says none; `null` means it did not say.
    val supportedServiceTiers: Set<String>?,
    // `null` when the model takes no verbosity or the catalog names no default for it.
    val defaultVerbosity: String?,
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
    @SerialName("service_tiers") val serviceTiers: List<CodexServiceTierInfo>? = null,
    @SerialName("support_verbosity") val supportVerbosity: Boolean = false,
    @SerialName("default_verbosity") val defaultVerbosity: String? = null,
)

@Serializable
private data class CodexServiceTierInfo(val id: String = "")

@Serializable
private data class GitHubRelease(@SerialName("tag_name") val tagName: String = "")

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
            codexRequestHeaders(credentials).forEach { (name, value) -> header(name, value) }
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
                    it.serviceTiers?.mapNotNull { tier -> tier.id.takeIf(String::isNotBlank) }?.toSet(),
                defaultVerbosity = it.defaultVerbosity?.takeIf { value -> it.supportVerbosity && value.isNotBlank() },
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
