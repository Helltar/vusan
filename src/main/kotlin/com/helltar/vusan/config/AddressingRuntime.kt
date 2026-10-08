package com.helltar.vusan.config

import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions

/**
 * Ambient addressing: answering a group message that calls the bot by name, or follows up on its answer,
 * without a mention, a reply or a command. `ADDRESSING_MODEL` is the whole switch.
 *
 * [names] are the spellings the chat uses besides the bot's own display name; empty means the ones
 * derived from its profile.
 */
data class AddressingConfig(
    val provider: LlmProviderConfig,
    val names: List<String>,
) {

    init {
        require(names.none { it.isBlank() }) { "ADDRESSING_NAMES must not contain a blank name" }
    }
}

/** The small model that tells whether a group message nobody tagged the bot in is meant for it. */
data class AddressingRuntime(
    val client: LlmClient,
    val model: LlmModel,
    val options: RequestOptions,
)

/**
 * Always a model of its own, never the chat model: the chat provider is whatever the deployment runs,
 * and a verdict that has to arrive in about a second was measured on small OpenAI models only — a large
 * reasoning model would be slow here, and DeepSeek got one message in twelve wrong. A yes-or-no over a
 * few lines of chat needs no reasoning, and every token of it is latency, so unless the deployment asks
 * for an effort the model gets the least its API is known to offer it.
 */
fun resolveAddressingRuntime(config: AddressingConfig, codexAuth: CodexAuthStore? = null): AddressingRuntime {
    val runtime = resolveLlmRuntime(config.provider, codexAuth)

    return AddressingRuntime(
        client = runtime.client,
        model = runtime.model,
        options =
            runtime.chatOptions.copy(
                reasoningEffort = config.provider.reasoningEffort ?: config.provider.leastEffort(runtime.model),
                promptCacheKey = runtime.chatOptions.promptCacheKey?.let { ADDRESSING_CACHE_KEY },
            ),
    )
}

// where the least is not known, the model's own default is left alone: a compatible server's models are
// anyone's guess, and a codex model takes only what the plan's catalog lists, `none` rarely among it.
private fun LlmProviderConfig.leastEffort(model: LlmModel): ReasoningEffort? =
    when (this) {
        is LlmProviderConfig.OpenAi -> ReasoningEffort.NONE.takeIf { model.takesEffort }
        is LlmProviderConfig.Anthropic -> ReasoningEffort.LOW.takeIf { model.takesEffort }
        is LlmProviderConfig.Codex -> supportedEfforts?.minOrNull()
        is LlmProviderConfig.OpenAiCompatible -> null
    }

private const val ADDRESSING_CACHE_KEY = "vusan-addressing"
