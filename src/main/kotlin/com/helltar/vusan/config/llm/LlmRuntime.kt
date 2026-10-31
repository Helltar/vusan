package com.helltar.vusan.config.llm

import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.codex.CodexAuthStore
import io.ktor.client.*

internal const val PROMPT_CACHE_KEY = "vusan"
internal const val COMPACTION_CACHE_KEY = "vusan-recap"

/**
 * One configured model ready to call: the client, the model, and the options a turn and a history recap send.
 * The recap gets no prompt caching and its own key: its body never repeats, and a shared key would dilute the
 * chat's.
 */
data class LlmRuntime(
    val providerLabel: String,
    val client: LlmClient,
    val model: LlmModel,
    val chatOptions: RequestOptions,
    val compactionOptions: RequestOptions = chatOptions.copy(cachePrompt = false),
)

/** The effort every chat call carries, as spelled on the wire, or `null` for the model's default. */
val LlmRuntime.reasoningEffort: String?
    get() = chatOptions.reasoningEffort?.requestValue

/** The serving tier every chat call asks for, or `null` for standard. */
val LlmRuntime.serviceTier: String?
    get() = chatOptions.serviceTier

/**
 * Builds the runtime for [config]. Each provider's file (`OpenAiProvider`, `AnthropicProvider`,
 * `OpenAiCompatibleProvider`, `CodexProvider`, `OpenRouterProvider`, `GoogleProvider`, `DeepSeekProvider`,
 * `XaiProvider`) turns its config into the client, the model and the per-call options; nothing outside them
 * knows one wire from another. [http] is for tests over a mock engine; a deployment leaves it to the
 * provider, which gives Codex the client that signs its requests.
 */
fun resolveLlmRuntime(config: LlmProviderConfig, codexAuth: CodexAuthStore? = null, http: HttpClient? = null): LlmRuntime =
    when (config) {
        is LlmProviderConfig.OpenAi -> openAiRuntime(config, http)
        is LlmProviderConfig.Anthropic -> anthropicRuntime(config, http)
        is LlmProviderConfig.OpenAiCompatible -> compatibleRuntime(config, http)
        is LlmProviderConfig.Codex -> codexRuntime(config, requireNotNull(codexAuth) { "a codex provider needs a CodexAuthStore" }, http)
        is LlmProviderConfig.OpenRouter -> openRouterRuntime(config, http)
        is LlmProviderConfig.Google -> googleRuntime(config, http)
        is LlmProviderConfig.DeepSeek -> deepSeekRuntime(config, http)
        is LlmProviderConfig.Xai -> xaiRuntime(config, http)
    }
