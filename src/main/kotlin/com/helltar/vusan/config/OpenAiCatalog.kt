package com.helltar.vusan.config

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*

private val log = KotlinLogging.logger("OpenAiCatalog")

private const val OPENAI_MODELS_URL = "https://api.openai.com/v1/models"

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

    val status =
        runCatching {
            http.get("$OPENAI_MODELS_URL/${model.trim().encodeURLPathPart()}") {
                bearerAuth(apiKey)
                expectSuccess = false
            }.status
        }.getOrElse { e ->
            log.warn { "OpenAI: could not check model=[$model] against the model list (${e.message}); assuming it exists" }
            return
        }

    check(status != HttpStatusCode.NotFound) {
        "LLM_MODEL=[$model] is not a model OpenAI serves this key. Check the id at https://platform.openai.com/docs/models"
    }

    if (status.isSuccess()) {
        log.info { "OpenAI: model=[$model] is newer than the built-in catalog and was confirmed against OpenAI's" }
    } else {
        log.warn { "OpenAI: the model check answered HTTP ${status.value} for model=[$model]; assuming it exists" }
    }
}
