package com.helltar.vusan.config

import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.OpenAiClient
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import io.ktor.client.*
import io.ktor.client.request.*

internal const val OPENAI_API_BASE_URL = "https://api.openai.com"
internal const val OPENAI_API_PATH = "/v1"

private const val OPENAI_MODELS_URL = "$OPENAI_API_BASE_URL$OPENAI_API_PATH/models"
private const val OPENAI_MODELS_PAGE = "https://platform.openai.com/docs/models"

// what every openai model since gpt-5.5 lists on its model page (checked 2026-09-17 for the 5.6 and 6
// generations); openai's model list reports no window, so this is assumed and LLM_CONTEXT_WINDOW_TOKENS
// says otherwise when a model does not keep it.
private const val OPENAI_CONTEXT_WINDOW = 1_050_000L
private const val OPENAI_MAX_OUTPUT = 128_000

/**
 * OpenAI's own platform: the Responses API, the one endpoint where tools work alongside reasoning, and
 * every turn here carries tools. A conversation gets a cache key of its own, and the gpt-5.6 generation
 * its cache options. The platform's model list states nothing about a model, so the window, the ceiling
 * and the eyes are what every model since gpt-5.5 has, and whether it reasons is read off the id.
 */
internal fun openAiRuntime(config: LlmProviderConfig.OpenAi, http: HttpClient?): LlmRuntime {
    val id = config.model.trim()
    val reasons = openAiReasons(id)

    require(config.reasoningEffort == null || reasons) {
        "${config.envPrefix}_REASONING_EFFORT does not apply to $id: a model that does not reason takes no effort"
    }

    val model =
        LlmModel(
            id = id,
            contextWindowTokens = config.contextWindowTokens ?: OPENAI_CONTEXT_WINDOW,
            maxOutputTokens = OPENAI_MAX_OUTPUT,
            seesImages = config.seesImages ?: true,
            takesEffort = reasons,
            efforts = config.efforts,
        )

    val options = RequestOptions(reasoningEffort = config.reasoningEffort, promptCacheKey = PROMPT_CACHE_KEY)

    return LlmRuntime(
        providerLabel = "OpenAI",
        client =
            RetryingLlmClient(
                OpenAiClient(
                    http = http ?: llmHttpClient(config.requestTimeout),
                    baseUrl = OPENAI_API_BASE_URL + OPENAI_API_PATH,
                    apiKey = config.apiKey,
                    endpoint = OpenAiEndpoint.RESPONSES,
                    statelessReasoning = true,
                    explicitPromptCaching = true,
                ),
            ),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY, cachePrompt = false),
    )
}

/** OpenAI's model list only confirms the id exists; what the model can do is assumed in [openAiRuntime]. */
internal suspend fun openAiPreflight(http: HttpClient, config: LlmProviderConfig.OpenAi): LlmProviderConfig.OpenAi {
    verifyModel(http, "OpenAI", config, OPENAI_MODELS_PAGE, OPENAI_MODELS_URL) { bearerAuth(config.apiKey) }

    return config
}

/**
 * Whether an OpenAI model reasons, and so takes an effort and hands back reasoning to replay.
 *
 * The o-series and everything from gpt-5 on do; the gpt-4 and gpt-3.5 families and the `chatgpt-`
 * aliases do not, and are sent neither an effort nor a request for the encrypted reasoning they never
 * write. The platform's model list says nothing about it, so the id is all there is to go on.
 */
internal fun openAiReasons(modelId: String): Boolean = !NON_REASONING_OPENAI_MODEL_ID.containsMatchIn(modelId)

private val NON_REASONING_OPENAI_MODEL_ID = Regex("""^(gpt-4|gpt-3\.5|chatgpt-)""", RegexOption.IGNORE_CASE)
