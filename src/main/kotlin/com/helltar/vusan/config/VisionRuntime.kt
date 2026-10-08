package com.helltar.vusan.config

import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel

/** The model that looks at images and video frames, with the client that reaches it. */
data class VisionRuntime(
    val providerLabel: String,
    val client: LlmClient,
    val model: LlmModel,
    // whether the client is the chat's own, which the caller closes once, or one of vision's to close too
    val ownClient: Boolean,
)

/**
 * Picks the model that looks at images. `VISION_MODEL` gives vision a model of its own, so a chat model
 * that cannot see (DeepSeek, most local models) does not take the bot's eyes away with it; a model named
 * for the role is taken at its word about seeing, whatever its provider. Without one, vision rides on
 * the chat model, and a chat model that cannot see leaves vision off entirely — `null` here means the
 * vision tools are never registered, which beats offering the agent a tool whose every call fails.
 */
fun resolveVisionRuntime(config: LlmProviderConfig?, chat: LlmRuntime, codexAuth: CodexAuthStore? = null): VisionRuntime? {
    if (config == null) {
        return chat.takeIf { it.model.seesImages }?.let { VisionRuntime(it.providerLabel, it.client, it.model, ownClient = false) }
    }

    val own = resolveLlmRuntime(config, codexAuth)

    return VisionRuntime(own.providerLabel, own.client, own.model.copy(seesImages = true), ownClient = true)
}
