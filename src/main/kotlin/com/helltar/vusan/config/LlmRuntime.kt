package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekParams
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.google.GoogleParams
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
        is LlmProviderConfig.Hosted -> resolveHostedRuntime(config, timeoutConfig)

        is LlmProviderConfig.OpenAiCompatible ->
            LlmRuntime(
                providerLabel = "OpenAI-compatible (${config.baseUrl})",
                koogProvider = LLMProvider.OpenAI,
                client = OpenAILLMClient(config.apiKey, OpenAIClientSettings(config.baseUrl, timeoutConfig)),
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

private fun resolveHostedRuntime(config: LlmProviderConfig.Hosted, timeoutConfig: ConnectionTimeoutConfig): LlmRuntime =
    when (config.provider) {
        HostedLlmProvider.OPENAI ->
            LlmRuntime(
                providerLabel = "OpenAI",
                koogProvider = LLMProvider.OpenAI,
                client = OpenAILLMClient(config.apiKey, OpenAIClientSettings(timeoutConfig = timeoutConfig)),
                model = resolveOpenAiModel(config.model),
                chatParams = OpenAIChatParams(promptCacheKey = "vusan")
            )

        HostedLlmProvider.ANTHROPIC ->
            LlmRuntime(
                providerLabel = "Anthropic",
                koogProvider = LLMProvider.Anthropic,
                client = AnthropicLLMClient(config.apiKey, AnthropicClientSettings(timeoutConfig = timeoutConfig)),
                model = resolveModel(AnthropicModels, "Anthropic", config.model),
                chatParams = AnthropicParams()
            )

        HostedLlmProvider.GOOGLE ->
            LlmRuntime(
                providerLabel = "Google",
                koogProvider = LLMProvider.Google,
                client = GoogleLLMClient(config.apiKey, GoogleClientSettings(timeoutConfig = timeoutConfig)),
                model = resolveModel(GoogleModels, "Google", config.model),
                chatParams = GoogleParams()
            )

        HostedLlmProvider.DEEPSEEK ->
            LlmRuntime(
                providerLabel = "DeepSeek",
                koogProvider = LLMProvider.DeepSeek,
                client = DeepSeekLLMClient(config.apiKey, DeepSeekClientSettings(timeoutConfig = timeoutConfig)),
                model = resolveModel(DeepSeekModels, "DeepSeek", config.model),
                chatParams = DeepSeekParams()
            )
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

internal fun resolveModel(definitions: LLModelDefinitions, providerLabel: String, rawValue: String): LLModel {
    val key = normalizeModelKey(rawValue)

    return requireNotNull(definitions.models.firstOrNull { normalizeModelKey(it.id) == key }) {
        "Unsupported $providerLabel model '$rawValue'. Supported values: " +
                definitions.models.map { it.id }.sorted().joinToString()
    }
}

private fun normalizeModelKey(value: String): String =
    value
        .trim()
        .lowercase()
        .replace('_', '-')
