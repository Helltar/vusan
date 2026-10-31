package com.helltar.vusan.config.llm

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.llmJson
import com.helltar.vusan.llm.openai.ResponsesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private const val XAI_API_BASE_URL = "https://api.x.ai/v1"
private const val XAI_MODELS_PAGE = "https://docs.x.ai/developers/models"

private val log = KotlinLogging.logger("ModelPreflight")

/**
 * xAI's API through the Responses API, the endpoint its quick start uses. It matches OpenAI's on the wire, so
 * the OpenAI client speaks it; what differs is what xAI reads, measured 2026-10-21:
 *
 * - `prompt_cache_key` is the conversation's, and xAI routes on it as on `x-grok-conv-id`, to the server
 *   holding the prefix. Caching is automatic, writes are free, and there are no breakpoints or lifetimes, so
 *   OpenAI's cache options are not sent.
 * - `store: false` keeps the conversation off xAI's servers (otherwise kept 30 days), and the encrypted
 *   reasoning comes back for the tool loop: a request missing the prior reasoning is what xAI names the first
 *   cause of cache misses.
 * - `parallel_tool_calls` stays at its default, on.
 *
 * Grok keeps an entry per request rather than per prefix (2026-10-27, via OpenRouter on xAI's upstream: a
 * rebuilt turn read 1,152 tokens, xAI's own prefix, against 7,680 for a continuation), so a turn continues
 * the last request.
 */
internal fun xaiRuntime(config: LlmProviderConfig.Xai, http: HttpClient?): LlmRuntime {
    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens,
            seesImages = config.seesImages ?: true,
            efforts = config.efforts,
            cachesOnlyRepeatedPrefix = true,
        )

    val options = RequestOptions(reasoningEffort = config.reasoningEffort, promptCacheKey = PROMPT_CACHE_KEY)

    return LlmRuntime(
        providerLabel = "xAI",
        client =
            RetryingLlmClient(
                ResponsesClient(
                    http = http ?: llmHttpClient(config.requestTimeout),
                    baseUrl = XAI_API_BASE_URL,
                    apiKey = config.apiKey,
                    statelessReasoning = true,
                    label = "xAI",
                ),
            ),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY, cachePrompt = false),
    )
}

/**
 * xAI's model list also answers for an alias, under the model's own id, with its window and efforts: `none`
 * where thinking can be turned off, nothing for a model that refuses the field. Vision is stated only by the
 * language-model list, asked second. Both answer on an account out of credits, whose first turn then fails
 * with a 402.
 */
internal suspend fun xaiPreflight(http: HttpClient, config: LlmProviderConfig.Xai): LlmProviderConfig.Xai {
    val facts = verifyModel(http, "xAI", config, XAI_MODELS_PAGE, "$XAI_API_BASE_URL/models") { bearerAuth(config.apiKey) } ?: return config

    (facts["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != config.model.trim() }?.let { log.info { "xAI: model=[${config.model}] is an alias of [$it]" } }

    val efforts =
        ((facts["capabilities"] as? JsonObject)?.get("reasoning_effort") as? JsonArray).orEmpty()
            .mapNotNull { level -> ReasoningEffort.entries.firstOrNull { it.requestValue == (level as? JsonPrimitive)?.contentOrNull } }
            .toSet()

    config.reasoningEffort?.let { configured ->
        check(configured in efforts) {
            "${config.envPrefix}_REASONING_EFFORT=[${configured.requestValue}] is not an effort ${config.model} takes. " +
                    if (efforts.isEmpty()) "It takes none, so leave the variable unset" else "Supported values: ${efforts.sorted().joinToString { it.requestValue }}"
        }
    }

    val inputs = languageModelInputs(http, config)

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: (facts["context_length"] as? JsonPrimitive)?.longOrNull,
        seesImages = inputs?.let { "image" in it } ?: config.seesImages,
        efforts = efforts,
    )
}

// what the language-model list says the model takes in, or null when it could not be asked
private suspend fun languageModelInputs(http: HttpClient, config: LlmProviderConfig.Xai): List<String>? {
    val response =
        runCatching {
            http.get("$XAI_API_BASE_URL/language-models/${config.model.trim().encodeURLPathPart()}") {
                bearerAuth(config.apiKey)
                expectSuccess = false
            }
        }.getOrElse { e ->
            e.rethrowIfCancellation()
            log.warn { "xAI: could not ask what model=[${config.model}] takes in (${e.message}); assuming it sees" }
            return null
        }

    if (!response.status.isSuccess()) {
        log.warn { "xAI: the language-model list answered HTTP ${response.status.value} for model=[${config.model}]; assuming it sees" }
        return null
    }

    return runCatching { llmJson.parseToJsonElement(response.bodyAsText()).jsonObject["input_modalities"] as? JsonArray }
        .getOrNull()
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
