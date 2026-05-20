package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams

data class LlmRuntime(
    val koogProvider: LLMProvider,
    val client: LLMClient,
    val model: LLModel,
    val chatParams: LLMParams
)

fun resolveLlmRuntime(config: LlmProviderConfig): LlmRuntime =
    when (config) {
        is LlmProviderConfig.OpenAi ->
            LlmRuntime(
                koogProvider = LLMProvider.OpenAI,
                client = OpenAILLMClient(config.apiKey),
                model = OpenAiModelResolver.resolve(config.model),
                chatParams = OpenAIChatParams(promptCacheKey = "vusan")
            )

        is LlmProviderConfig.Ollama ->
            LlmRuntime(
                koogProvider = LLMProvider.Ollama,
                client = OllamaClient(baseUrl = config.baseUrl),
                model =
                    LLModel(
                        provider = LLMProvider.Ollama,
                        id = config.model,
                        capabilities = listOf(
                            LLMCapability.Temperature,
                            LLMCapability.Schema.JSON.Standard,
                            LLMCapability.Tools
                        )
                    ),
                chatParams = LLMParams()
            )

        is LlmProviderConfig.OpenAiCompatible ->
            LlmRuntime(
                koogProvider = LLMProvider.OpenAI,
                client =
                    OpenAILLMClient(
                        apiKey = config.apiKey,
                        settings = OpenAIClientSettings(baseUrl = config.baseUrl)
                    ),
                model =
                    LLModel(
                        provider = LLMProvider.OpenAI,
                        id = config.model,
                        capabilities = listOf(
                            LLMCapability.Completion,
                            LLMCapability.Temperature,
                            LLMCapability.Schema.JSON.Standard,
                            LLMCapability.Tools,
                            LLMCapability.OpenAIEndpoint.Completions
                        )
                    ),
                chatParams = LLMParams()
            )
    }
