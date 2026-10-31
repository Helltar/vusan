package com.helltar.vusan.config.llm

import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.codex.CodexAuthStore

/**
 * The model that looks at images and video frames, its client, and the [options] every look carries: no
 * prompt caching, since a picture is looked at once, and a cache key of its own.
 */
data class VisionRuntime(
    val providerLabel: String,
    val client: LlmClient,
    val model: LlmModel,
    val options: RequestOptions,
    // whether the client is vision's own and must be closed too, or the chat's, closed once by the caller
    val ownClient: Boolean,
)

/**
 * Picks the vision model. `VISION_MODEL` gives vision its own model, so a blind chat model
 * (`deepseek-v4-pro`, most local models) does not take the bot's eyes with it; a model named for the role is
 * trusted to see unless its vendor said at startup it takes no images (`seesImages == false`), which stops
 * startup instead of failing every look. Without one, vision rides on the chat model, and a blind chat model
 * leaves vision off: `null` means the vision tools are never registered, better than a tool whose every call
 * fails.
 */
fun resolveVisionRuntime(config: LlmProviderConfig?, chat: LlmRuntime, codexAuth: CodexAuthStore? = null): VisionRuntime? {
    // on the chat model a look keeps the model's own effort rather than the turn's; a dedicated vision model
    // gets its configured effort
    if (config == null) {
        return chat.takeIf { it.model.seesImages }?.let {
            VisionRuntime(it.providerLabel, it.client, it.model, it.visionOptions().copy(reasoningEffort = null), ownClient = false)
        }
    }

    require(config.seesImages != false) {
        "${config.envPrefix}_MODEL=[${config.model}] takes no images, by its own vendor's word; " +
                "name one that does, or leave ${config.envPrefix}_MODEL unset to look with the chat model"
    }

    val own = resolveLlmRuntime(config, codexAuth)

    return VisionRuntime(own.providerLabel, own.client, own.model.copy(seesImages = true), own.visionOptions(), ownClient = true)
}

private fun LlmRuntime.visionOptions(): RequestOptions =
    chatOptions.copy(cachePrompt = false, promptCacheKey = chatOptions.promptCacheKey?.let { VISION_CACHE_KEY })

private const val VISION_CACHE_KEY = "vusan-vision"
