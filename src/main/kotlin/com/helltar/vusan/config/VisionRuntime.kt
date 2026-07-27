package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import kotlin.time.Duration

/** The model that looks at images and video frames, with the executor that reaches it. */
data class VisionRuntime(
    val providerLabel: String,
    val executor: PromptExecutor,
    val model: LLModel
)

/**
 * Picks the model that looks at images. `OPENAI_VISION_API_KEY` gives vision an OpenAI model of its own,
 * the way `OPENAI_STT_API_KEY` does for speech, so a chat model that cannot see (DeepSeek, most local
 * models) does not take the bot's eyes away with it. Without that key vision rides on the chat model, and
 * a chat model that cannot see leaves vision off entirely — `null` here means the vision tools are never
 * registered, which beats offering the agent a tool whose every call fails.
 */
fun resolveVisionRuntime(
    config: OpenAiVisionConfig?,
    chat: LlmRuntime,
    chatExecutor: PromptExecutor,
    requestTimeout: Duration
): VisionRuntime? {
    if (config == null) {
        return chat
            .takeIf { it.model.supports(LLMCapability.Vision.Image) }
            ?.let { VisionRuntime(it.providerLabel, chatExecutor, it.model) }
    }

    val model = resolveOpenAiModel(config.model)

    require(model.supports(LLMCapability.Vision.Image)) {
        "OPENAI_VISION_MODEL=[${config.model}] cannot read images"
    }

    val client = OpenAILLMClient(config.apiKey, OpenAIClientSettings(timeoutConfig = connectionTimeouts(requestTimeout)))

    return VisionRuntime(
        providerLabel = "OpenAI",
        executor = MultiLLMPromptExecutor(model.provider to client),
        model = model
    )
}
