package com.helltar.vusan.config

import ai.koog.http.client.HttpClientFactoryResolver
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
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.time.Duration

data class LlmRuntime(
    val providerLabel: String,
    val client: LLMClient,
    val model: LLModel,
    val chatParams: LLMParams
)

fun resolveLlmRuntime(config: LlmProviderConfig): LlmRuntime {
    val timeoutConfig = connectionTimeouts(config.requestTimeout)

    return when (config) {
        is LlmProviderConfig.Hosted -> resolveHostedRuntime(config, timeoutConfig)

        is LlmProviderConfig.OpenAiCompatible ->
            LlmRuntime(
                providerLabel = "OpenAI-compatible (${config.baseUrl}, ${config.endpoint.name.lowercase()})",
                client =
                    openAiClient(
                        apiKey = config.apiKey,
                        settings = OpenAIClientSettings(config.baseUrl, timeoutConfig),
                        explicitPromptCaching = config.openAiPromptCacheKey != null
                    ),
                model = openAiCompatibleModel(config),
                chatParams = openAiCompatibleParams(config)
            )
    }
}

// the koog client picks the endpoint from the params type and refuses to send params whose endpoint the
// model does not declare, so the two are resolved together.
private fun openAiCompatibleModel(config: LlmProviderConfig.OpenAiCompatible): LLModel =
    LLModel(
        provider = LLMProvider.OpenAI,
        id = config.model,
        capabilities =
            buildList {
                add(LLMCapability.Completion)
                add(LLMCapability.Temperature)
                add(LLMCapability.Schema.JSON.Standard)
                add(LLMCapability.Tools)

                when (config.endpoint) {
                    OpenAiEndpoint.COMPLETIONS -> add(LLMCapability.OpenAIEndpoint.Completions)
                    OpenAiEndpoint.RESPONSES -> add(LLMCapability.OpenAIEndpoint.Responses)
                }

                // without Thinking the client drops both the reasoning effort and the reasoning items the
                // model returns, so a reasoning model would re-derive its thinking on every tool result.
                if (config.endpoint == OpenAiEndpoint.RESPONSES || config.reasoningEffort != null)
                    add(LLMCapability.Thinking)
            }
    )

// parallel tool calls stay off on both endpoints: third-party models garble the sibling calls of a batch,
// and the agent executes tool calls sequentially anyway.
private fun openAiCompatibleParams(config: LlmProviderConfig.OpenAiCompatible): LLMParams =
    when (config.endpoint) {
        OpenAiEndpoint.COMPLETIONS ->
            OpenAIChatParams(
                parallelToolCalls = false,
                promptCacheKey = config.openAiPromptCacheKey,
                reasoningEffort = config.reasoningEffort
            )

        OpenAiEndpoint.RESPONSES ->
            OpenAIResponsesParams(
                parallelToolCalls = false,
                promptCacheKey = config.openAiPromptCacheKey,
                reasoning = config.reasoningEffort?.let { ReasoningConfig(effort = it) }
            )
    }

// prompt_cache_key is an openai extension, so do not leak it to arbitrary compatible servers that may
// reject unknown fields.
private val LlmProviderConfig.OpenAiCompatible.openAiPromptCacheKey: String?
    get() =
        OPENAI_PROMPT_CACHE_KEY.takeIf {
            baseUrl.trim().trimEnd('/').equals(OPENAI_API_BASE_URL, ignoreCase = true)
        }

// both request and socket timeouts default to 900 s in the koog client; cap them so a stalled LLM
// call fails fast and the agent can deliver an error reply instead of leaving the bot silent.
internal fun connectionTimeouts(requestTimeout: Duration): ConnectionTimeoutConfig =
    ConnectionTimeoutConfig(
        requestTimeoutMillis = requestTimeout.inWholeMilliseconds,
        socketTimeoutMillis = requestTimeout.inWholeMilliseconds
    )

private fun resolveHostedRuntime(config: LlmProviderConfig.Hosted, timeoutConfig: ConnectionTimeoutConfig): LlmRuntime =
    when (config.provider) {
        HostedLlmProvider.OPENAI ->
            LlmRuntime(
                providerLabel = "OpenAI",
                client =
                    openAiClient(
                        apiKey = config.apiKey,
                        settings = OpenAIClientSettings(timeoutConfig = timeoutConfig),
                        explicitPromptCaching = true
                    ),
                model = resolveOpenAiModel(config.model),
                chatParams = OpenAIChatParams(promptCacheKey = OPENAI_PROMPT_CACHE_KEY)
            )

        HostedLlmProvider.ANTHROPIC ->
            LlmRuntime(
                providerLabel = "Anthropic",
                client = AnthropicLLMClient(config.apiKey, AnthropicClientSettings(timeoutConfig = timeoutConfig)),
                model = resolveModel(AnthropicModels, "Anthropic", config.model),
                chatParams = AnthropicParams()
            )

        HostedLlmProvider.GOOGLE ->
            LlmRuntime(
                providerLabel = "Google",
                client = GoogleLLMClient(config.apiKey, GoogleClientSettings(timeoutConfig = timeoutConfig)),
                model = resolveModel(GoogleModels, "Google", config.model),
                chatParams = GoogleParams()
            )

        HostedLlmProvider.DEEPSEEK ->
            LlmRuntime(
                providerLabel = "DeepSeek",
                client = DeepSeekLLMClient(config.apiKey, DeepSeekClientSettings(timeoutConfig = timeoutConfig)),
                model = resolveModel(DeepSeekModels, "DeepSeek", config.model),
                chatParams = DeepSeekParams()
            )
    }

private fun openAiClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    explicitPromptCaching: Boolean
): OpenAILLMClient {
    if (!explicitPromptCaching) return OpenAILLMClient(apiKey, settings)

    return OpenAILLMClient(
        apiKey = apiKey,
        settings = settings,
        httpClientFactory = OpenAiPromptCachingHttpClientFactory(HttpClientFactoryResolver.resolve())
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

private const val OPENAI_API_BASE_URL = "https://api.openai.com"
private const val OPENAI_PROMPT_CACHE_KEY = "vusan"
