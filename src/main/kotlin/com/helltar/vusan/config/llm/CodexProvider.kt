package com.helltar.vusan.config.llm

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.ImageProviderConfig
import java.nio.file.Path
import com.helltar.vusan.infra.HttpStatusException
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.ResponsesClient
import com.helltar.vusan.llm.codex.CODEX_BACKEND_BASE_URL
import com.helltar.vusan.llm.codex.CodexAuthException
import com.helltar.vusan.llm.codex.CodexAuthStore
import com.helltar.vusan.llm.codex.CodexModel
import com.helltar.vusan.llm.codex.codexCallObserver
import com.helltar.vusan.llm.codex.codexClientVersion
import com.helltar.vusan.llm.codex.codexRequestPlugin
import com.helltar.vusan.llm.codex.codexRoutingHint
import com.helltar.vusan.llm.codex.fetchCodexModels
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*

private val log = KotlinLogging.logger("ModelPreflight")

// the smallest window in the plan's catalog (2026-10-29: gpt-6.1-sol and gpt-6.1-terra list 500k, the rest
// 272k), assumed when the account's catalog could not be read
private const val CODEX_CONTEXT_WINDOW = 272_000L

/** Where the signed-in ChatGPT account is read from, and the CLI version claimed for it. */
internal data class CodexSignIn(val authFile: Path, val clientVersion: String?)

/** Every model role this deployment runs on the plan: chat, fallback, vision, addressing. */
internal fun AppConfig.codexRoles(): List<LlmProviderConfig.Codex> =
    listOfNotNull(llmProvider, llmFallback, vision, addressing?.provider).filterIsInstance<LlmProviderConfig.Codex>()

/**
 * The plan's sign-in wherever the plan is used, a model role or images alone. One account serves all, so the
 * first configured use names the file and version.
 */
internal fun AppConfig.codexSignIn(): CodexSignIn? =
    codexRoles().firstOrNull()?.let { CodexSignIn(it.authFile, it.clientVersion) }
        ?: (image as? ImageProviderConfig.Codex)?.let { CodexSignIn(it.authFile, it.clientVersion) }

/**
 * A ChatGPT subscription via the Codex backend: the Responses API, streaming only, with a valid ChatGPT token
 * on every request instead of an API key. The window, vision and efforts come from the catalog via the
 * preflight.
 */
internal fun codexRuntime(config: LlmProviderConfig.Codex, auth: CodexAuthStore, http: HttpClient?): LlmRuntime {
    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens ?: CODEX_CONTEXT_WINDOW,
            seesImages = config.seesImages ?: true,
            efforts = config.efforts,
            // the backend takes `additional_tools` as the platform does: measured 2026-10-27 on every model a
            // plus plan lists, the offered tool was called and the prefix before it read back
            offersToolsMidConversation = true,
            // the backend refuses breakpoints and caches near each request's end: measured 2026-10-27 on
            // gpt-6-luna and gpt-6.1-sol, a turn appending to the last request read all of it back, a rebuilt
            // one only the system block
            cachesOnlyRepeatedPrefix = true,
        )

    val options =
        RequestOptions(
            reasoningEffort = config.reasoningEffort,
            verbosity = config.verbosity,
            serviceTier = config.serviceTier?.requestValue,
            // as the cli asks: one request for a batch of lookups, run side by side when read-only
            parallelToolCalls = true,
            promptCacheKey = PROMPT_CACHE_KEY,
        )

    return LlmRuntime(
        providerLabel = "ChatGPT subscription (Codex)",
        client =
            RetryingLlmClient(
                ResponsesClient(
                    http = http ?: llmHttpClient(config.requestTimeout, codexRequestPlugin(auth, codexRoutingHint(config.model, config.serviceTier?.requestValue))),
                    baseUrl = CODEX_BACKEND_BASE_URL,
                    apiKey = null,
                    streamed = true,
                    statelessReasoning = true,
                    label = "Codex",
                    onResponse = codexCallObserver(auth.limits),
                ),
            ),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY, cachePrompt = false),
    )
}

/**
 * Proves the ChatGPT session works before taking messages, then applies the window and capabilities the
 * account's catalog advertises. Reading the token also forces a refresh of a stale `auth.json`, so a host
 * idle for days fails at startup with "run `codex login`" rather than on someone's first turn.
 */
internal suspend fun codexPreflight(http: HttpClient, config: LlmProviderConfig.Codex, auth: CodexAuthStore?): LlmProviderConfig.Codex {
    val store = requireNotNull(auth) { "a codex provider needs a CodexAuthStore" }
    val plan = store.planType()

    log.info { "Codex: signed in to ChatGPT${plan?.let { " (plan=[$it])" }.orEmpty()} auth=[${config.authFile}]" }

    val discovered = verifyCodexModel(http, store, config) ?: return config

    log.info { "Codex: model=[${discovered.id}] (${discovered.displayName})" }

    return applyCodexModelMetadata(config, discovered)
}

internal fun applyCodexModelMetadata(
    config: LlmProviderConfig.Codex,
    model: CodexModel,
): LlmProviderConfig.Codex {
    val configuredEffort = config.reasoningEffort
    val supportedEfforts = model.supportedReasoningEfforts

    // the catalog lists thinking levels; `none` turns thinking off and is no level, so the first turn decides
    // it
    if (configuredEffort != null && configuredEffort != ReasoningEffort.NONE && supportedEfforts != null) {
        require(configuredEffort in supportedEfforts) {
            "${config.envPrefix}_REASONING_EFFORT=[${configuredEffort.requestValue}] is not supported by " +
                    "${config.envPrefix}_MODEL=[${model.id}]. Supported values: " +
                    supportedEfforts.sorted().joinToString { it.requestValue }
        }
    }

    val configuredTier = config.serviceTier
    val supportedTiers = model.supportedServiceTiers

    if (configuredTier != null && supportedTiers != null) {
        require(configuredTier.requestValue in supportedTiers) {
            "CODEX_SERVICE_TIER=[${configuredTier.requestValue}] is not supported by " +
                    "${config.envPrefix}_MODEL=[${model.id}]. Supported values: " +
                    supportedTiers.sorted().joinToString().ifEmpty { "none" }
        }
    }

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: model.contextWindowTokens,
        seesImages = model.supportsVision,
        verbosity = model.defaultVerbosity,
        efforts = supportedEfforts,
    )
}

/**
 * Checks the configured model against the account's catalog before the first turn: the entry when it matches,
 * `null` when the catalog is unreadable. `/models` is undocumented, so a shape change there must not take a
 * working bot down; a wrong name still surfaces on the first turn. A model the account plainly cannot run
 * *is* fatal, since that failure is the confusing one.
 */
suspend fun verifyCodexModel(http: HttpClient, auth: CodexAuthStore, config: LlmProviderConfig.Codex): CodexModel? {
    val model = config.model

    val catalog =
        runCatching { fetchCodexModels(http, auth) }
            .getOrElse { e ->
                if (e is CodexAuthException) throw e

                // a refused token is the sign-in failing, not the endpoint changing shape
                if (e is HttpStatusException && e.status in REFUSED_STATUSES) {
                    throw CodexAuthException("The ChatGPT session was refused (HTTP ${e.status}). Run `codex login` on this host again.", e)
                }

                log.warn { "Codex: could not read the model catalog (${e.message}); skipping the model check" }
                return null
            }

    if (catalog.isEmpty()) {
        log.warn { "Codex: the model catalog came back empty; skipping the model check" }
        return null
    }

    val match = catalog.firstOrNull { it.id.equals(model.trim(), ignoreCase = true) }

    checkNotNull(match) {
        // the catalog is filtered by the claimed version, so a model too new for it is missing rather than
        // refused; said here since the list alone reads as an entitlement problem.
        "${config.envPrefix}_MODEL=[$model] is not available on this ChatGPT subscription. " +
                "Available models: ${catalog.map { it.id }.sorted().joinToString()}. " +
                "Models newer than client_version=[${codexClientVersion()}] are hidden from that list."
    }

    return match
}

private val REFUSED_STATUSES = setOf(401, 403)
