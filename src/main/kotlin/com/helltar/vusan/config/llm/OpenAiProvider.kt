package com.helltar.vusan.config.llm

import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.ResponsesClient
import io.ktor.client.*
import io.ktor.client.request.*

internal const val OPENAI_API_BASE_URL = "https://api.openai.com"
internal const val OPENAI_API_PATH = "/v1"

private const val OPENAI_MODELS_URL = "$OPENAI_API_BASE_URL$OPENAI_API_PATH/models"
private const val OPENAI_MODELS_PAGE = "https://platform.openai.com/docs/models"

// what every openai model since gpt-5.5 lists on its model page (checked 2026-09-17 for 5.6 and 6); the model
// list reports no window, so this is assumed unless LLM_CONTEXT_WINDOW_TOKENS says otherwise.
private const val OPENAI_CONTEXT_WINDOW = 1_050_000L
private const val OPENAI_MAX_OUTPUT = 128_000

/**
 * OpenAI's platform via the Responses API, the one endpoint where tools work alongside reasoning. Each
 * conversation gets its own cache key, and GPT-5.6 its cache options. The model list states nothing about a
 * model, so the window, ceiling and vision are what every model since gpt-5.5 has, and it is taken to reason.
 */
internal fun openAiRuntime(config: LlmProviderConfig.OpenAi, http: HttpClient?): LlmRuntime {
    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens ?: OPENAI_CONTEXT_WINDOW,
            maxOutputTokens = OPENAI_MAX_OUTPUT,
            seesImages = config.seesImages ?: true,
            efforts = config.efforts,
            // `additional_tools`, taken by every model from gpt-5.5 on (measured 2026-10-24)
            offersToolsMidConversation = true,
        )

    val options = RequestOptions(reasoningEffort = config.reasoningEffort, promptCacheKey = PROMPT_CACHE_KEY)

    return LlmRuntime(
        providerLabel = "OpenAI",
        client =
            RetryingLlmClient(
                ResponsesClient(
                    http = http ?: llmHttpClient(config.requestTimeout),
                    baseUrl = OPENAI_API_BASE_URL + OPENAI_API_PATH,
                    apiKey = config.apiKey,
                    statelessReasoning = true,
                    explicitPromptCaching = true,
                ),
            ),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY, cachePrompt = false),
    )
}

/** OpenAI's model list only confirms the id exists; capabilities are assumed in [openAiRuntime]. */
internal suspend fun openAiPreflight(http: HttpClient, config: LlmProviderConfig.OpenAi): LlmProviderConfig.OpenAi {
    verifyModel(http, "OpenAI", config, OPENAI_MODELS_PAGE, OPENAI_MODELS_URL) { bearerAuth(config.apiKey) }

    return config
}
