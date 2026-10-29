package com.helltar.vusan.config.llm

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.llmJson
import com.helltar.vusan.llm.long
import com.helltar.vusan.llm.string
import com.helltar.vusan.llm.openai.ChatCompletionsClient
import com.helltar.vusan.llm.openai.OpenAiEndpoint
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

    val httpClient = http ?: llmHttpClient(config.requestTimeout)

    val client =
        when (config.endpoint) {
            OpenAiEndpoint.RESPONSES ->
                ResponsesClient(
                    http = httpClient,
                    baseUrl = baseUrl + OPENAI_API_PATH,
                    apiKey = config.apiKey,
                    statelessReasoning = official,
                    explicitPromptCaching = official,
                    label = "OpenAI-compatible",
                )

            OpenAiEndpoint.COMPLETIONS ->
                ChatCompletionsClient(
                    http = httpClient,
                    baseUrl = baseUrl + OPENAI_API_PATH,
                    apiKey = config.apiKey,
                    explicitPromptCaching = official,
                    echoesReasoningContent = !official,
                    legacyOutputCeiling = !official,
                    label = "OpenAI-compatible",
                )
        }

    return LlmRuntime(
        providerLabel = "OpenAI-compatible ($baseUrl, ${config.endpoint.name.lowercase()})",
        client = RetryingLlmClient(client),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY.takeIf { official }, cachePrompt = false),
    )
}

/**
 * What a compatible server's model list says of the model, where it says anything: whether it takes
 * images (`input_modalities`) and its window (`context_window` or `context_length`). A vendor with a
 * provider of its own — DeepSeek, xAI — is read in that provider, so nothing here follows one vendor's
 * shape; Ollama, which has none, names its models and nothing more, and its own API is asked last (see
 * [ollamaFacts]).
 *
 * Nothing about a compatible server is promised — it may serve no list, list a model without any of
 * these, or answer to an alias it never lists — so each fact it leaves out keeps the runtime's
 * assumption, and a model missing from the list is a warning rather than a failed startup. Which efforts
 * a model takes is left to its first turn.
 */
internal suspend fun compatiblePreflight(http: HttpClient, config: LlmProviderConfig.OpenAiCompatible): LlmProviderConfig.OpenAiCompatible {
    val baseUrl = config.baseUrl.trim().trimEnd('/')
    val label = "OpenAI-compatible ($baseUrl)"
    val model = config.model.trim()
    val listed = listedModels(http, label, "$baseUrl/v1/models") { bearerAuth(config.apiKey) } ?: return config
    val facts = listed.firstOrNull { model == it.string("id") }

    if (facts == null) {
        val ids = listed.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
        log.warn { "$label: ${config.envPrefix}_MODEL=[$model] is not among the models it lists (${ids.joinToString()}); assuming it exists" }

        return config
    }

    val modalities = facts.texts("input_modalities")
    val window = config.contextWindowTokens ?: facts.long("context_window") ?: facts.long("context_length")

    log.info { "$label: model=[$model] confirmed against the server's model list" }

    val local = if (modalities == null || window == null) ollamaFacts(http, baseUrl, model) else null

    local?.let {
        log.info { "$label: model=[$model] read off Ollama's own API: sees=[${it.seesImages}] window=[${it.window ?: "not stated"}]" }
    }

    return config.copy(
        contextWindowTokens = window ?: local?.window,
        seesImages = modalities?.let { "image" in it } ?: local?.seesImages ?: config.seesImages,
    )
}

/** What Ollama's own API says of a model: whether it sees, and the window it actually runs with, where that is certain. */
private data class OllamaFacts(val seesImages: Boolean, val window: Long?)

/**
 * The model as Ollama's own API describes it, or `null` on a server that is not Ollama.
 *
 * `/api/show` names what the model can do, `vision` among it. The window it reports there is the one the
 * model was trained on, not the one Ollama runs it with — that is the Modelfile's `num_ctx`, else the
 * server's `OLLAMA_CONTEXT_LENGTH` — so only those two are taken: `num_ctx` when the Modelfile sets one,
 * and what `/api/ps` reports for the model while it is loaded. A window planned past the real one is cut
 * by Ollama from the front of the prompt, the system prompt first, without a word.
 */
private suspend fun ollamaFacts(http: HttpClient, baseUrl: String, model: String): OllamaFacts? {
    val shown =
        runCatching {
            http.post("$baseUrl/api/show") {
                contentType(ContentType.Application.Json)
                setBody(JsonObject(mapOf("model" to JsonPrimitive(model))))
                expectSuccess = false
            }
        }.getOrElse {
            it.rethrowIfCancellation()
            return null
        }

    if (!shown.status.isSuccess()) return null

    val facts = runCatching { llmJson.parseToJsonElement(shown.bodyAsText()).jsonObject }.getOrNull() ?: return null
    val capabilities = facts.texts("capabilities") ?: return null
    val numCtx = facts.string("parameters")?.let { NUM_CTX.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    return OllamaFacts(seesImages = "vision" in capabilities, window = numCtx ?: loadedWindow(http, baseUrl, model))
}

// the window a loaded model runs with, which is exact; `null` while the model is not in memory
private suspend fun loadedWindow(http: HttpClient, baseUrl: String, model: String): Long? {
    val response =
        runCatching { http.get("$baseUrl/api/ps") { expectSuccess = false } }
            .getOrElse {
                it.rethrowIfCancellation()
                return null
            }

    if (!response.status.isSuccess()) return null

    val loaded = runCatching { llmJson.parseToJsonElement(response.bodyAsText()).jsonObject["models"] as? JsonArray }.getOrNull() ?: return null

    return loaded.filterIsInstance<JsonObject>()
        .firstOrNull { model == it.string("name") || model == it.string("model") }
        ?.long("context_length")
}

private val NUM_CTX = Regex("""(?m)^num_ctx\s+(\d+)""")

private fun JsonObject.texts(key: String): List<String>? = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
