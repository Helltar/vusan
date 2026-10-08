package com.helltar.vusan.config

import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import io.ktor.client.*

internal const val PROMPT_CACHE_KEY = "vusan"
internal const val COMPACTION_CACHE_KEY = "vusan-recap"

/**
 * One configured model, ready to call: the client, the model it is for, and the options a turn and a
 * history recap send with it. The recap gets no prompt caching and its own cache key — its body never
 * repeats, so a cache write would buy a read nobody makes, and a shared key would dilute the chat's.
 */
data class LlmRuntime(
    val providerLabel: String,
    val client: LlmClient,
    val model: LlmModel,
    val chatOptions: RequestOptions,
    val compactionOptions: RequestOptions = chatOptions.copy(cachePrompt = false),
)

/** The effort every chat call carries, as the request spells it, or `null` for the model's own default. */
val LlmRuntime.reasoningEffort: String?
    get() = chatOptions.reasoningEffort?.requestValue

/** The serving tier every chat call asks for, or `null` for the standard one. */
val LlmRuntime.serviceTier: String?
    get() = chatOptions.serviceTier

/**
 * Builds the runtime for [config]. Each provider's file — `OpenAiProvider`, `AnthropicProvider`,
 * `OpenAiCompatibleProvider`, `CodexProvider` — turns its own config into the client, the model as the
 * bot needs to know it and the options every call carries; nothing outside those files knows one wire
 * from another. [http] is for tests, which hand in a client over a mock engine; a deployment leaves it
 * to the provider, which gives a Codex runtime the client that signs its requests.
 */
fun resolveLlmRuntime(config: LlmProviderConfig, codexAuth: CodexAuthStore? = null, http: HttpClient? = null): LlmRuntime =
    when (config) {
        is LlmProviderConfig.OpenAi -> openAiRuntime(config, http)
        is LlmProviderConfig.Anthropic -> anthropicRuntime(config, http)
        is LlmProviderConfig.OpenAiCompatible -> compatibleRuntime(config, http)
        is LlmProviderConfig.Codex -> codexRuntime(config, requireNotNull(codexAuth) { "a codex provider needs a CodexAuthStore" }, http)
    }
