package com.helltar.vusan.config

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*

private val log = KotlinLogging.logger("HostedModelCheck")

private const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"
private const val ANTHROPIC_MODELS_URL = "https://api.anthropic.com/v1/models"
private const val ANTHROPIC_API_VERSION = "2023-06-01"

/**
 * Asks OpenAI whether [model] exists for this key, for a model koog's catalog does not know.
 *
 * The catalog used to be the check, and a typo failed startup with the list of what is supported; a
 * model declared without it would instead fail on the first message, with a 404 in the log and a
 * canned error in the chat. One request keeps the failure at startup. Anything but a plain "no such
 * model" — the network down, a 5xx — only warns, since the deployment may well be right.
 */
suspend fun verifyOpenAiModel(http: HttpClient, apiKey: String, model: String) {
    if (cataloguedOpenAiModel(model) != null) return

    verifyModel(http, "OpenAI", model, "https://platform.openai.com/docs/models", OPENAI_MODELS_URL) {
        bearerAuth(apiKey)
    }
}

/** The same question as [verifyOpenAiModel], asked of Anthropic's model list. */
suspend fun verifyAnthropicModel(http: HttpClient, apiKey: String, model: String) {
    if (cataloguedAnthropicModel(model) != null) return

    verifyModel(http, "Anthropic", model, "https://platform.claude.com/docs/en/about-claude/models/overview", ANTHROPIC_MODELS_URL) {
        header("x-api-key", apiKey)
        header("anthropic-version", ANTHROPIC_API_VERSION)
    }
}

private suspend fun verifyModel(
    http: HttpClient,
    provider: String,
    model: String,
    modelsPage: String,
    modelsUrl: String,
    authorize: HttpRequestBuilder.() -> Unit,
) {
    val status =
        runCatching {
            http.get("$modelsUrl/${model.trim().encodeURLPathPart()}") {
                authorize()
                expectSuccess = false
            }.status
        }.getOrElse { e ->
            e.rethrowIfCancellation()
            log.warn { "$provider: could not check model=[$model] against the model list (${e.message}); assuming it exists" }
            return
        }

    check(status != HttpStatusCode.NotFound) {
        "LLM_MODEL=[$model] is not a model $provider serves this key. Check the id at $modelsPage"
    }

    if (status.isSuccess()) {
        log.info { "$provider: model=[$model] is newer than the built-in catalog and was confirmed against $provider's" }
    } else {
        log.warn { "$provider: the model check answered HTTP ${status.value} for model=[$model]; assuming it exists" }
    }
}
