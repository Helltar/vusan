package com.helltar.vusan.config.llm

import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.ChatCompletionsClient
import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val DEEPSEEK_API_BASE_URL = "https://api.deepseek.com"
private const val DEEPSEEK_MODELS_URL = "$DEEPSEEK_API_BASE_URL/models"
private const val DEEPSEEK_MODELS_PAGE = "https://api-docs.deepseek.com/quick_start/pricing"

/**
 * DeepSeek's own API: Chat Completions at the base URL its docs give, the OpenAI client speaking it. The
 * thinking a model writes in `reasoning_content` goes back with the turn's calls, and a call another
 * provider wrote carries an empty one; the output ceiling travels as the `max_tokens` DeepSeek reads, since
 * it ignores `max_completion_tokens`. `none` turns thinking off, which the API documents among the efforts.
 *
 * Both models keep an entry per request rather than per prefix — measured on 2026-10-27, `deepseek-v4-pro`
 * read nothing of a turn rebuilt from storage and the whole of one that continued the last request, and
 * `deepseek-flash` the last turn more — so a turn continues the conversation's last request.
 */
internal fun deepSeekRuntime(config: LlmProviderConfig.DeepSeek, http: HttpClient?): LlmRuntime {
    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens,
            maxOutputTokens = config.maxOutputTokens,
            seesImages = config.seesImages == true,
            efforts = config.efforts,
            cachesOnlyRepeatedPrefix = true,
        )

    return LlmRuntime(
        providerLabel = "DeepSeek",
        client =
            RetryingLlmClient(
                ChatCompletionsClient(
                    http = http ?: llmHttpClient(config.requestTimeout),
                    baseUrl = DEEPSEEK_API_BASE_URL,
                    apiKey = config.apiKey,
                    echoesReasoningContent = true,
                    legacyOutputCeiling = true,
                    label = "DeepSeek",
                ),
            ),
        model = model,
        chatOptions = RequestOptions(reasoningEffort = config.reasoningEffort),
    )
}

/**
 * DeepSeek's model list states the window, the output ceiling, what the model takes in and which thinking
 * levels it takes. It names levels only, never the switch that turns thinking off, so `none` passes; another
 * effort the list does not name stops the startup — DeepSeek would quietly serve `medium` as `high`.
 */
internal suspend fun deepSeekPreflight(http: HttpClient, config: LlmProviderConfig.DeepSeek): LlmProviderConfig.DeepSeek {
    val facts =
        verifyModel(http, "DeepSeek", config, DEEPSEEK_MODELS_PAGE, DEEPSEEK_MODELS_URL) {
            bearerAuth(config.apiKey)
        } ?: return config

    val efforts =
        ((facts["effort"] as? JsonObject)?.get("supported_levels") as? JsonArray)
            ?.mapNotNull { level -> ReasoningEffort.entries.firstOrNull { it.requestValue == (level as? JsonPrimitive)?.contentOrNull } }
            ?.toSet()

    config.reasoningEffort?.takeIf { it != ReasoningEffort.NONE }?.let { configured ->
        check(efforts == null || configured in efforts) {
            "${config.envPrefix}_REASONING_EFFORT=[${configured.requestValue}] is not an effort ${config.model} takes. " +
                    "Supported values: none, ${efforts.orEmpty().joinToString { it.requestValue }}"
        }
    }

    val modalities = (facts["input_modalities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: (facts["context_window"] as? JsonPrimitive)?.longOrNull,
        maxOutputTokens = (facts["max_output_tokens"] as? JsonPrimitive)?.intOrNull,
        seesImages = modalities?.let { "image" in it },
        efforts = efforts ?: config.efforts,
    )
}
