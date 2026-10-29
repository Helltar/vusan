package com.helltar.vusan.config.llm

import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.google.GOOGLE_EFFORTS
import com.helltar.vusan.llm.google.GoogleClient
import com.helltar.vusan.llm.llmHttpClient
import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val GOOGLE_MODELS_URL = "${GoogleClient.DEFAULT_BASE_URL}/v1beta/models"
private const val GOOGLE_MODELS_PAGE = "https://ai.google.dev/gemini-api/docs/models"

/**
 * Google's Gemini API on an AI Studio key, through its Interactions endpoint. The window and the output
 * ceiling are what its model list said at startup. That list says nothing of images, and every Gemini and
 * Gemma 4 model takes them, so a model here sees unless the deployment says otherwise.
 */
internal fun googleRuntime(config: LlmProviderConfig.Google, http: HttpClient?): LlmRuntime {
    val effort = config.reasoningEffort

    require(effort == null || effort in GOOGLE_EFFORTS) {
        "${config.envPrefix}_REASONING_EFFORT=[${effort?.requestValue}] is not an effort Google takes. Supported values: " +
                GOOGLE_EFFORTS.joinToString { it.requestValue }
    }

    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens,
            maxOutputTokens = config.maxOutputTokens,
            seesImages = config.seesImages ?: true,
            efforts = config.efforts,
        )

    return LlmRuntime(
        providerLabel = "Google",
        client = RetryingLlmClient(GoogleClient(http ?: llmHttpClient(config.requestTimeout), config.apiKey)),
        model = model,
        chatOptions = RequestOptions(reasoningEffort = effort),
    )
}

/**
 * Google's model list states the window (`inputTokenLimit`), the output ceiling, what the model generates
 * and whether it thinks, but not which thinking levels it takes — those differ from model to model and the
 * first turn tells. A model that writes no text, and an effort configured for one that does not think,
 * stop the startup here rather than failing every turn.
 */
internal suspend fun googlePreflight(http: HttpClient, config: LlmProviderConfig.Google): LlmProviderConfig.Google {
    val facts =
        verifyModel(http, "Google", config, GOOGLE_MODELS_PAGE, GOOGLE_MODELS_URL) {
            header("x-goog-api-key", config.apiKey)
        } ?: return config

    val methods = (facts["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val thinks = (facts["thinking"] as? JsonPrimitive)?.booleanOrNull

    check(methods == null || "generateContent" in methods) {
        "${config.envPrefix}_MODEL=[${config.model}] does not generate content; Vusan needs a Gemini or Gemma model that writes text"
    }

    config.reasoningEffort?.let { configured ->
        check(thinks != false) {
            "${config.envPrefix}_REASONING_EFFORT=[${configured.requestValue}] is set, but ${config.model} does not think. Leave it unset"
        }
    }

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: (facts["inputTokenLimit"] as? JsonPrimitive)?.longOrNull,
        maxOutputTokens = (facts["outputTokenLimit"] as? JsonPrimitive)?.intOrNull,
        efforts = if (thinks == false) emptySet() else config.efforts,
    )
}
