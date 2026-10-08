package com.helltar.vusan.config

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.ReasoningEffort
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private val log = KotlinLogging.logger("ModelPreflight")

private const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"
private const val ANTHROPIC_MODELS_URL = "https://api.anthropic.com/v1/models"
private const val ANTHROPIC_API_VERSION = "2023-06-01"
private const val OPENAI_MODELS_PAGE = "https://platform.openai.com/docs/models"
private const val ANTHROPIC_MODELS_PAGE = "https://platform.claude.com/docs/en/about-claude/models/overview"

private val json = Json { ignoreUnknownKeys = true }

/**
 * Asks the vendor about the configured model before the first turn, so a typo fails at startup with
 * the vendor's answer rather than on someone's first message with an opaque error.
 *
 * OpenAI only confirms the id exists. Anthropic's model list also states the window, the output
 * ceiling and what the model takes — adaptive thinking, which efforts — and those replace what the
 * runtime would otherwise assume. A compatible server's list, where it serves one, may say whether the
 * model takes images, its window and its efforts, as DeepSeek's does. Codex reads the account's own
 * catalog. Anything but a plain "no such model" — the network down, a 5xx — only warns, since the
 * deployment may well be right.
 */
suspend fun LlmProviderConfig.preflighted(http: HttpClient, codexAuth: CodexAuthStore?): LlmProviderConfig =
    when (this) {
        is LlmProviderConfig.OpenAi -> {
            verifyModel(http, "OpenAI", this, OPENAI_MODELS_PAGE, OPENAI_MODELS_URL) { bearerAuth(apiKey) }
            this
        }

        is LlmProviderConfig.Anthropic -> anthropicPreflight(http, this)
        is LlmProviderConfig.OpenAiCompatible -> compatiblePreflight(http, this)
        is LlmProviderConfig.Codex -> codexPreflight(http, this, codexAuth)
    }

private suspend fun anthropicPreflight(http: HttpClient, config: LlmProviderConfig.Anthropic): LlmProviderConfig.Anthropic {
    val facts =
        verifyModel(http, "Anthropic", config, ANTHROPIC_MODELS_PAGE, ANTHROPIC_MODELS_URL) {
            header("x-api-key", config.apiKey)
            header("anthropic-version", ANTHROPIC_API_VERSION)
        } ?: return config

    val capabilities = facts["capabilities"] as? JsonObject
    val thinking = ((capabilities?.get("thinking") as? JsonObject)?.get("types") as? JsonObject)
    val effort = capabilities?.get("effort") as? JsonObject

    val adaptive = (thinking?.get("adaptive") as? JsonObject)?.supported
    val takesEffort = effort?.supported?.let { it && adaptive != false }

    config.reasoningEffort?.let { configured ->
        val offered = (effort?.get(configured.requestValue) as? JsonObject)?.supported

        check(offered != false && takesEffort != false) {
            "${config.envPrefix}_REASONING_EFFORT=[${configured.requestValue}] is not an effort ${config.model} takes. " +
                    "Supported values: ${effort.supportedEfforts().joinToString().ifEmpty { "none" }}"
        }
    }

    return config.copy(
        contextWindowTokens = config.contextWindowTokens ?: (facts["max_input_tokens"] as? JsonPrimitive)?.longOrNull,
        maxOutputTokens = (facts["max_tokens"] as? JsonPrimitive)?.intOrNull,
        takesEffort = takesEffort,
    )
}

private val JsonObject.supported: Boolean?
    get() = (this["supported"] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject?.supportedEfforts(): List<String> =
    ReasoningEffort.entries.map { it.requestValue }.filter { (this?.get(it) as? JsonObject)?.supported == true }

/** The vendor's model object when it serves the configured model, `null` when it could not be asked, an error when it does not. */
private suspend fun verifyModel(
    http: HttpClient,
    provider: String,
    config: LlmProviderConfig,
    modelsPage: String,
    modelsUrl: String,
    authorize: HttpRequestBuilder.() -> Unit,
): JsonObject? {
    val model = config.model
    val response =
        runCatching {
            http.get("$modelsUrl/${model.trim().encodeURLPathPart()}") {
                authorize()
                expectSuccess = false
            }
        }.getOrElse { e ->
            e.rethrowIfCancellation()
            log.warn { "$provider: could not check model=[$model] against the model list (${e.message}); assuming it exists" }
            return null
        }

    check(response.status != HttpStatusCode.NotFound) {
        "${config.envPrefix}_MODEL=[$model] is not a model $provider serves this key. Check the id at $modelsPage"
    }

    if (!response.status.isSuccess()) {
        log.warn { "$provider: the model check answered HTTP ${response.status.value} for model=[$model]; assuming it exists" }
        return null
    }

    log.info { "$provider: model=[$model] confirmed against the vendor's model list" }

    return runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
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
private suspend fun compatiblePreflight(http: HttpClient, config: LlmProviderConfig.OpenAiCompatible): LlmProviderConfig.OpenAiCompatible {
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
    )
}

/** The entries of a server's `GET /models`, or `null` when it could not be asked or serves no such list. */
private suspend fun listedModels(http: HttpClient, label: String, url: String, authorize: HttpRequestBuilder.() -> Unit): List<JsonObject>? {
    val response =
        runCatching {
            http.get(url) {
                authorize()
                expectSuccess = false
            }
        }.getOrElse { e ->
            e.rethrowIfCancellation()
            log.warn { "$label: could not read its model list (${e.message}); keeping the assumptions" }
            return null
        }

    if (!response.status.isSuccess()) {
        log.warn { "$label: its model list answered HTTP ${response.status.value}; keeping the assumptions" }
        return null
    }

    val data = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject["data"] as? JsonArray }.getOrNull()

    if (data == null) log.warn { "$label: what its model list answered is not a list of models; keeping the assumptions" }

    return data?.filterIsInstance<JsonObject>()
}

/**
 * Prove the ChatGPT session works before the bot starts taking messages, then apply the context window
 * and capabilities advertised by the account's own model catalog.
 *
 * Reading the token here also forces a refresh on a stale `auth.json`, so a host that has been idle for
 * days fails at startup with a "run `codex login`" message instead of on someone's first turn.
 */
private suspend fun codexPreflight(http: HttpClient, config: LlmProviderConfig.Codex, auth: CodexAuthStore?): LlmProviderConfig.Codex {
    val store = requireNotNull(auth) { "a codex provider needs a CodexAuthStore" }
    val plan = store.planType()

    log.info { "Codex: signed in to ChatGPT${plan?.let { " (plan=[$it])" }.orEmpty()} auth=[${config.authFile}]" }

    val discovered = verifyCodexModel(http, store, config) ?: return config

    log.info { "Codex: model=[${discovered.id}] (${discovered.displayName})" }

    return applyCodexModelMetadata(config, discovered)
}
