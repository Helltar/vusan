package com.helltar.vusan.config.llm

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.llmJson
import com.helltar.vusan.llm.long
import com.helltar.vusan.llm.string
import com.helltar.vusan.llm.openai.OpenAiClient
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

private val log = KotlinLogging.logger("ModelPreflight")

/**
 * Any server that speaks the OpenAI API under `LLM_BASE_URL`, either endpoint. Parallel tool calls are
 * left to the server's default: DeepSeek ignores the field, and one that honors it, such as xAI, would
 * only be slowed by `false`. The cache key is an OpenAI extension, so it travels only to OpenAI itself and
 * not to a server that may reject unknown fields; the same goes for `store` and the encrypted reasoning
 * the Responses API hands back on request. A model here sees only when its server's list said so.
 */
internal fun compatibleRuntime(config: LlmProviderConfig.OpenAiCompatible, http: HttpClient?): LlmRuntime {
    val baseUrl = config.baseUrl.trim().trimEnd('/')
    val official = baseUrl.equals(OPENAI_API_BASE_URL, ignoreCase = true)

    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens,
            seesImages = config.seesImages == true,
            efforts = config.efforts,
        )

    val options =
        RequestOptions(
            reasoningEffort = config.reasoningEffort,
            promptCacheKey = PROMPT_CACHE_KEY.takeIf { official },
        )

    return LlmRuntime(
        providerLabel = "OpenAI-compatible ($baseUrl, ${config.endpoint.name.lowercase()})",
        client =
            RetryingLlmClient(
                OpenAiClient(
                    http = http ?: llmHttpClient(config.requestTimeout),
                    baseUrl = baseUrl + OPENAI_API_PATH,
                    apiKey = config.apiKey,
                    endpoint = config.endpoint,
                    statelessReasoning = official,
                    explicitPromptCaching = official,
                    echoesReasoningContent = !official,
                    legacyOutputCeiling = !official,
                    label = "OpenAI-compatible",
                ),
            ),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY.takeIf { official }, cachePrompt = false),
    )
}

/**
 * What a compatible server's model list says of the model, where it says anything: whether it takes
 * images, its window and which efforts it takes.
 *
 * Servers spell these differently, and each spelling met is read: DeepSeek's `context_window` and
 * `effort.supported_levels`, and xAI's `context_length` and `capabilities.reasoning_effort`, with the
 * model found under one of its `aliases` too. xAI states what a model takes in only on a list of its
 * own, `/v1/language-models`, which is asked about the model when the main list leaves it out; on a
 * server without that list the question is a 404 and changes nothing.
 *
 * Nothing about a compatible server is promised — it may serve no list, list a model without any of
 * these, or answer to an alias it never lists — so each fact it leaves out keeps the runtime's
 * assumption, and a model missing from the list is a warning rather than a failed startup. An effort it
 * does state and the configured one is not among is a failure, as it would be on every turn.
 */
internal suspend fun compatiblePreflight(http: HttpClient, config: LlmProviderConfig.OpenAiCompatible): LlmProviderConfig.OpenAiCompatible {
    val baseUrl = config.baseUrl.trim().trimEnd('/')
    val label = "OpenAI-compatible ($baseUrl)"
    val model = config.model.trim()
    val listed = listedModels(http, label, "$baseUrl/v1/models") { bearerAuth(config.apiKey) } ?: return config
    val facts = listed.firstOrNull { model == it.string("id") || model in it.texts("aliases").orEmpty() }

    if (facts == null) {
        val ids = listed.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
        log.warn { "$label: ${config.envPrefix}_MODEL=[$model] is not among the models it lists (${ids.joinToString()}); assuming it exists" }

        return config
    }

    val efforts =
        ((facts["effort"] as? JsonObject)?.texts("supported_levels") ?: (facts["capabilities"] as? JsonObject)?.texts("reasoning_effort"))
            ?.mapNotNull { level -> ReasoningEffort.entries.firstOrNull { it.requestValue == level } }
            ?.toSet()

    // the list names thinking levels; none turns thinking off and is no level, so the first turn decides it
    config.reasoningEffort?.takeIf { it != ReasoningEffort.NONE }?.let { configured ->
        check(efforts == null || configured in efforts) {
            "${config.envPrefix}_REASONING_EFFORT=[${configured.requestValue}] is not an effort $model takes. " +
                    "Supported values: ${efforts.orEmpty().joinToString { it.requestValue }.ifEmpty { "none" }}"
        }
    }

    val modalities =
        facts.texts("input_modalities")
            ?: languageModelInputs(http, "$baseUrl/v1/language-models/${model.encodeURLPathPart()}") { bearerAuth(config.apiKey) }

    log.info { "$label: model=[$model] confirmed against the server's model list" }

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: facts.long("context_window") ?: facts.long("context_length"),
        seesImages = modalities?.let { "image" in it } ?: config.seesImages,
        efforts = efforts ?: config.efforts,
    )
}

// what a server's list of language models says one takes in, or `null` when it keeps no such list
private suspend fun languageModelInputs(http: HttpClient, url: String, authorize: HttpRequestBuilder.() -> Unit): List<String>? {
    val response =
        runCatching {
            http.get(url) {
                authorize()
                expectSuccess = false
            }
        }.getOrElse {
            it.rethrowIfCancellation()
            return null
        }

    if (!response.status.isSuccess()) return null

    return runCatching { llmJson.parseToJsonElement(response.bodyAsText()).jsonObject.texts("input_modalities") }.getOrNull()
}

private fun JsonObject.texts(key: String): List<String>? = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
