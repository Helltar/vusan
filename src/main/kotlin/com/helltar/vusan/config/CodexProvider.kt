package com.helltar.vusan.config

import com.helltar.vusan.agent.ContextWindowPolicy
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.OpenAiClient
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*

private val log = KotlinLogging.logger("ModelPreflight")

/**
 * A ChatGPT subscription through the Codex backend, which speaks the Responses API, streaming only,
 * without an API key: the HTTP client stamps a currently valid ChatGPT token on every request. The
 * catalog's context window, eyes and efforts arrive in the config, filled in by the preflight.
 */
internal fun codexRuntime(config: LlmProviderConfig.Codex, auth: CodexAuthStore, http: HttpClient?): LlmRuntime {
    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens ?: ContextWindowPolicy.DEFAULT_CONTEXT_WINDOW_TOKENS,
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
                    http = http ?: llmHttpClient(config.requestTimeout, codexRequestPlugin(auth, codexRoutingHint(config.model, config.serviceTier))),
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
