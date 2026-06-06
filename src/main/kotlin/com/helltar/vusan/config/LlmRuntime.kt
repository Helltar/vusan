package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

data class LlmRuntime(
    val providerLabel: String,
    val koogProvider: LLMProvider,
    val client: LLMClient,
    val model: LLModel,
    val chatParams: LLMParams
)

fun resolveLlmRuntime(config: LlmProviderConfig): LlmRuntime {
    // Both request and socket timeouts default to 900 s in the koog client; cap them so a stalled LLM
    // call fails fast and the agent can deliver an error reply instead of leaving the bot silent.
    val timeoutConfig =
        ConnectionTimeoutConfig(
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds,
            socketTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        )

    return when (config) {
        is LlmProviderConfig.OpenAi ->
            LlmRuntime(
                providerLabel = "OpenAI",
                koogProvider = LLMProvider.OpenAI,
                client = OpenAILLMClient(config.apiKey, settings = OpenAIClientSettings(timeoutConfig = timeoutConfig)),
                model = resolveOpenAiModel(config.model),
                chatParams = OpenAIChatParams(promptCacheKey = "vusan")
            )

        is LlmProviderConfig.OpenAiCompatible ->
            LlmRuntime(
                providerLabel = "OpenAI-compatible (${config.baseUrl})",
                koogProvider = LLMProvider.OpenAI,
                client =
                    OpenAILLMClient(
                        apiKey = config.apiKey,
                        settings = OpenAIClientSettings(baseUrl = config.baseUrl, timeoutConfig = timeoutConfig)
                    ),
                model =
                    LLModel(
                        provider = LLMProvider.OpenAI,
                        id = config.model,
                        capabilities =
                            listOf(
                                LLMCapability.Completion,
                                LLMCapability.Temperature,
                                LLMCapability.Schema.JSON.Standard,
                                LLMCapability.Tools,
                                LLMCapability.OpenAIEndpoint.Completions
                            )
                    ),
                chatParams = OpenAIChatParams(parallelToolCalls = false)
            )
    }
}

private val openAiModelsByKey: Map<String, LLModel> by lazy {
    OpenAIModels.Chat::class.memberProperties
        .asSequence()
        .filter { it.returnType.classifier == LLModel::class }
        .mapNotNull { it.javaField?.get(OpenAIModels.Chat) as? LLModel }
        .associateBy { it.id.lowercase() }
}

internal fun resolveOpenAiModel(rawValue: String): LLModel =
    requireNotNull(openAiModelsByKey[normalizeModelKey(rawValue)]) {
        "Unsupported OpenAI model '$rawValue'. Supported values: ${openAiModelsByKey.keys.sorted().joinToString()}"
    }

private fun normalizeModelKey(value: String): String =
    value
        .trim()
        .lowercase()
        .replace('_', '-')
