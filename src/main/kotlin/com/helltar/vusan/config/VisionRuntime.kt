package com.helltar.vusan.config

import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions

/**
 * The model that looks at images and video frames, with the client that reaches it and the [options]
 * every look carries: no prompt caching, since a picture is looked at once, and a cache key of its own.
 */
data class VisionRuntime(
    val providerLabel: String,
    val client: LlmClient,
    val model: LlmModel,
    val options: RequestOptions,
    // whether the client is the chat's own, which the caller closes once, or one of vision's to close too
    val ownClient: Boolean,
)

/**
 * Picks the model that looks at images. `VISION_MODEL` gives vision a model of its own, so a chat model
 * that cannot see (`deepseek-v4-pro`, most local models) does not take the bot's eyes away with it; a
 * model named for the role is taken at its word about seeing, unless its own vendor said at startup that
 * it takes no images — then the startup stops, rather than every look failing. Without one, vision rides
 * on the chat model, and a chat model that cannot see leaves vision off entirely — `null` here means the
 * vision tools are never registered, which beats offering the agent a tool whose every call fails.
 */
fun resolveVisionRuntime(config: LlmProviderConfig?, chat: LlmRuntime, codexAuth: CodexAuthStore? = null): VisionRuntime? {
    // riding on the chat model, a look keeps the model's own effort rather than the one tuned for turns;
    // a vision model of its own is sent the effort configured for it
    if (config == null) {
        return chat.takeIf { it.model.seesImages }?.let {
            VisionRuntime(it.providerLabel, it.client, it.model, it.visionOptions().copy(reasoningEffort = null), ownClient = false)
        }
    }

    require(!config.saidToBeBlind) {
        "${config.envPrefix}_MODEL=[${config.model}] takes no images, by its own vendor's word; " +
                "name one that does, or leave ${config.envPrefix}_MODEL unset to look with the chat model"
    }

    val own = resolveLlmRuntime(config, codexAuth)

    return VisionRuntime(own.providerLabel, own.client, own.model.copy(seesImages = true), own.visionOptions(), ownClient = true)
}

// only a compatible server's model list and the codex catalog say so; nobody else states it either way
private val LlmProviderConfig.saidToBeBlind: Boolean
    get() =
        when (this) {
            is LlmProviderConfig.OpenAiCompatible -> seesImages == false
            is LlmProviderConfig.Codex -> !supportsVision
            is LlmProviderConfig.OpenAi, is LlmProviderConfig.Anthropic -> false
        }

private fun LlmRuntime.visionOptions(): RequestOptions =
    chatOptions.copy(cachePrompt = false, promptCacheKey = chatOptions.promptCacheKey?.let { VISION_CACHE_KEY })

private const val VISION_CACHE_KEY = "vusan-vision"
