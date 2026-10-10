package com.helltar.vusan.config

import java.nio.file.Path
import com.helltar.vusan.infra.HttpStatusException
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.OpenAiClient
import com.helltar.vusan.llm.openai.OpenAiEndpoint
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

/** Where the one signed-in ChatGPT account is read from, and the CLI version claimed for it. */
internal data class CodexSignIn(val authFile: Path, val clientVersion: String?)

/** Every model role this deployment runs on the plan: the chat, its fallback, vision, addressing. */
internal fun AppConfig.codexRoles(): List<LlmProviderConfig.Codex> =
    listOfNotNull(llmProvider, llmFallback, vision, addressing?.provider).filterIsInstance<LlmProviderConfig.Codex>()

/**
 * The plan's sign-in, wherever the plan is used — a model role, or image generation alone. One account
 * serves all of them, so the first configured use names the file and the version.
 */
internal fun AppConfig.codexSignIn(): CodexSignIn? =
    codexRoles().firstOrNull()?.let { CodexSignIn(it.authFile, it.clientVersion) }
        ?: (image as? ImageProviderConfig.Codex)?.let { CodexSignIn(it.authFile, it.clientVersion) }

/**
 * A ChatGPT subscription through the Codex backend, which speaks the Responses API, streaming only,
 * without an API key: the HTTP client stamps a currently valid ChatGPT token on every request. The
 * catalog's context window, eyes and efforts arrive in the config, filled in by the preflight.
 */
internal fun codexRuntime(config: LlmProviderConfig.Codex, auth: CodexAuthStore, http: HttpClient?): LlmRuntime {
    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens ?: LlmProviderConfig.DEFAULT_CONTEXT_WINDOW_TOKENS,
            seesImages = config.seesImages ?: true,
            efforts = config.efforts,
        )

    val options =
        RequestOptions(
            reasoningEffort = config.reasoningEffort,
            verbosity = config.verbosity,
            serviceTier = config.serviceTier?.requestValue,
            parallelToolCalls = false,
            promptCacheKey = PROMPT_CACHE_KEY,
        )

    return LlmRuntime(
        providerLabel = "ChatGPT subscription (Codex)",
        client =
            RetryingLlmClient(
                OpenAiClient(
                    http = http ?: llmHttpClient(config.requestTimeout, codexRequestPlugin(auth, codexRoutingHint(config.model, config.serviceTier?.requestValue))),
                    baseUrl = CODEX_BACKEND_BASE_URL,
                    apiKey = null,
                    endpoint = OpenAiEndpoint.RESPONSES,
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
 * Proves the ChatGPT session works before the bot starts taking messages, then applies the context
 * window and the capabilities the account's own model catalog advertises.
 *
 * Reading the token here also forces a refresh on a stale `auth.json`, so a host that has been idle for
 * days fails at startup with a "run `codex login`" message instead of on someone's first turn.
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

    if (configuredEffort != null && supportedEfforts != null) {
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
 * Check the configured model against the account's catalog before the first turn.
 *
 * Returns the catalog entry when it matches, `null` when the catalog could not be read. A missing
 * catalog is not fatal: `/models` is an undocumented endpoint, and a shape change there must not take
 * a working bot down — a wrong model name still surfaces on the first turn. A model the account
 * plainly cannot run *is* fatal, because that is the confusing failure worth catching early.
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
        // the catalog is filtered by the version we claim, so a model too new for it is missing rather
        // than refused — worth naming here, since the list alone reads as an entitlement problem.
        "${config.envPrefix}_MODEL=[$model] is not available on this ChatGPT subscription. " +
                "Available models: ${catalog.map { it.id }.sorted().joinToString()}. " +
                "Models newer than client_version=[${codexClientVersion()}] are hidden from that list."
    }

    return match
}

private val REFUSED_STATUSES = setOf(401, 403)
