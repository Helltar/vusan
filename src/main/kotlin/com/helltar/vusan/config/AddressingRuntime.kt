package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlin.time.Duration

/** The small model that tells whether a group message nobody tagged the bot in is meant for it. */
data class AddressingRuntime(
    val executor: PromptExecutor,
    val model: LLModel,
    val params: LLMParams,
)

/**
 * Always a model of its own, never the chat model: the chat provider is whatever the deployment runs,
 * and a verdict that has to arrive in about a second was measured on small OpenAI models only — a large
 * reasoning model would be slow here, and DeepSeek got one message in twelve wrong.
 */
fun resolveAddressingRuntime(config: AddressingConfig, requestTimeout: Duration): AddressingRuntime {
    val model = openAiModel(config.model)
    val client = OpenAILLMClient(config.apiKey, OpenAIClientSettings(timeoutConfig = connectionTimeouts(requestTimeout)))

    return AddressingRuntime(
        executor = MultiLLMPromptExecutor(model.provider to client),
        model = model,
        // a yes-or-no over a few lines of chat needs no reasoning, and every token of it is latency
        params = openAiHostedParams(model, ADDRESSING_CACHE_KEY, ReasoningEffort.NONE),
    )
}

private const val ADDRESSING_CACHE_KEY = "vusan-addressing"
