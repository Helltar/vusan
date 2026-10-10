package com.helltar.vusan.config

import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.anthropic.ANTHROPIC_EFFORTS
import com.helltar.vusan.llm.anthropic.AnthropicClient
import com.helltar.vusan.llm.llmHttpClient
import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val ANTHROPIC_MODELS_URL = "${AnthropicClient.DEFAULT_BASE_URL}/v1/models"
private const val ANTHROPIC_MODELS_PAGE = "https://platform.claude.com/docs/en/about-claude/models/overview"

// what every claude model since opus 4.7 lists, and what the vendor's model list confirms at startup
private const val ANTHROPIC_CONTEXT_WINDOW = 1_000_000L
private const val ANTHROPIC_MAX_OUTPUT = 128_000

/**
 * Anthropic's Messages API, on a model that thinks adaptively and takes an effort: every Claude model
 * from 4.6 on. Which efforts it takes is what its model list said at startup.
 */
internal fun anthropicRuntime(config: LlmProviderConfig.Anthropic, http: HttpClient?): LlmRuntime {
    val effort = config.reasoningEffort

    require(effort == null || effort in ANTHROPIC_EFFORTS) {
        "${config.envPrefix}_REASONING_EFFORT=[${effort?.requestValue}] is not an effort Anthropic takes. Supported values: " +
                ANTHROPIC_EFFORTS.joinToString { it.requestValue }
    }

    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens ?: ANTHROPIC_CONTEXT_WINDOW,
            maxOutputTokens = config.maxOutputTokens ?: ANTHROPIC_MAX_OUTPUT,
            seesImages = config.seesImages ?: true,
            efforts = config.efforts,
        )

    return LlmRuntime(
        providerLabel = "Anthropic",
        client = RetryingLlmClient(AnthropicClient(http ?: llmHttpClient(config.requestTimeout), config.apiKey)),
        model = model,
        chatOptions = RequestOptions(reasoningEffort = effort),
    )
}

/**
 * Anthropic's model list states the window, the output ceiling and what the model takes — adaptive
 * thinking, which efforts, whether it sees — and those replace what the runtime would otherwise assume.
 * A model without adaptive thinking, and a configured effort the list does not offer, stop the startup
 * here rather than failing every turn.
 */
internal suspend fun anthropicPreflight(http: HttpClient, config: LlmProviderConfig.Anthropic): LlmProviderConfig.Anthropic {
    val facts =
        verifyModel(http, "Anthropic", config, ANTHROPIC_MODELS_PAGE, ANTHROPIC_MODELS_URL) {
            header("x-api-key", config.apiKey)
            header("anthropic-version", AnthropicClient.API_VERSION)
        } ?: return config

    val capabilities = facts["capabilities"] as? JsonObject
    val thinking = ((capabilities?.get("thinking") as? JsonObject)?.get("types") as? JsonObject)
    val effort = capabilities?.get("effort") as? JsonObject

    val adaptive = (thinking?.get("adaptive") as? JsonObject)?.supported
    val efforts = effort?.let { listed -> ReasoningEffort.entries.filter { (listed[it.requestValue] as? JsonObject)?.supported == true }.toSet() }

    check(adaptive != false && effort?.supported != false) {
        "${config.envPrefix}_MODEL=[${config.model}] does not think adaptively or take an effort; Vusan needs a Claude model from 4.6 on"
    }

    config.reasoningEffort?.let { configured ->
        val offered = (effort?.get(configured.requestValue) as? JsonObject)?.supported

        check(offered != false) {
            "${config.envPrefix}_REASONING_EFFORT=[${configured.requestValue}] is not an effort ${config.model} takes. " +
                    "Supported values: ${efforts.orEmpty().joinToString { it.requestValue }.ifEmpty { "none" }}"
        }
    }

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: (facts["max_input_tokens"] as? JsonPrimitive)?.longOrNull,
        maxOutputTokens = (facts["max_tokens"] as? JsonPrimitive)?.intOrNull,
        seesImages = (capabilities?.get("image_input") as? JsonObject)?.supported,
        efforts = efforts,
    )
}

private val JsonObject.supported: Boolean?
    get() = (this["supported"] as? JsonPrimitive)?.booleanOrNull
