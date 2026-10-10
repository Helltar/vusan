package com.helltar.vusan.config

import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.OpenAiClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

private val log = KotlinLogging.logger("ModelPreflight")

/**
 * Any server that speaks the OpenAI API under `LLM_BASE_URL`, either endpoint. Parallel tool calls stay
 * off: third-party models garble the sibling calls of a batch, and the agent executes tool calls
 * sequentially anyway. The cache key is an OpenAI extension, so it travels only to OpenAI itself and not
 * to a server that may reject unknown fields; the same goes for `store` and the encrypted reasoning the
 * Responses API hands back on request. A model here sees only when its server's list said so, and
 * reasons unless the server is the platform itself and the id says otherwise.
 */
internal fun compatibleRuntime(config: LlmProviderConfig.OpenAiCompatible, http: HttpClient?): LlmRuntime {
    val baseUrl = config.baseUrl.trim().trimEnd('/')
    val official = baseUrl.equals(OPENAI_API_BASE_URL, ignoreCase = true)

    val model =
        LlmModel(
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens ?: LlmProviderConfig.DEFAULT_CONTEXT_WINDOW_TOKENS,
            seesImages = config.seesImages == true,
            // only the platform itself is known to refuse reasoning fields to a model that does not reason
            takesEffort = !official || openAiReasons(config.model.trim()),
            efforts = config.efforts,
        )

    val options =
        RequestOptions(
            reasoningEffort = config.reasoningEffort,
            parallelToolCalls = false,
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
    val facts = listed.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == model }

    if (facts == null) {
        val ids = listed.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
        log.warn { "$label: ${config.envPrefix}_MODEL=[$model] is not among the models it lists (${ids.joinToString()}); assuming it exists" }

        return config
    }

    val efforts =
        ((facts["effort"] as? JsonObject)?.get("supported_levels") as? JsonArray)
            ?.mapNotNull { level -> ReasoningEffort.entries.firstOrNull { it.requestValue == (level as? JsonPrimitive)?.contentOrNull } }
            ?.toSet()

    config.reasoningEffort?.let { configured ->
        check(efforts == null || configured in efforts) {
            "${config.envPrefix}_REASONING_EFFORT=[${configured.requestValue}] is not an effort $model takes. " +
                    "Supported values: ${efforts.orEmpty().joinToString { it.requestValue }.ifEmpty { "none" }}"
        }
    }

    val modalities = (facts["input_modalities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    log.info { "$label: model=[$model] confirmed against the server's model list" }

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: (facts["context_window"] as? JsonPrimitive)?.longOrNull,
        seesImages = modalities?.let { "image" in it } ?: config.seesImages,
        efforts = efforts ?: config.efforts,
    )
}
