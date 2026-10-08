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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * runtime would otherwise assume. Codex reads the account's own catalog. Anything but a plain "no such
 * model" — the network down, a 5xx — only warns, since the deployment may well be right.
 */
suspend fun LlmProviderConfig.preflighted(http: HttpClient, codexAuth: CodexAuthStore?): LlmProviderConfig =
    when (this) {
        is LlmProviderConfig.OpenAi -> {
            verifyModel(http, "OpenAI", this, OPENAI_MODELS_PAGE, OPENAI_MODELS_URL) { bearerAuth(apiKey) }
            this
        }

        is LlmProviderConfig.Anthropic -> anthropicPreflight(http, this)
        is LlmProviderConfig.OpenAiCompatible -> this
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
