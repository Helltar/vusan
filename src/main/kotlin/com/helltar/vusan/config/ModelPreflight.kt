package com.helltar.vusan.config

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.codex.CodexAuthStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val log = KotlinLogging.logger("ModelPreflight")

internal val preflightJson = Json { ignoreUnknownKeys = true }

/**
 * Asks the vendor about the configured model before the first turn, so a typo fails at startup with
 * the vendor's answer rather than on someone's first message with an opaque error.
 *
 * Each provider's file holds its own check, and what it learns — the window, the output ceiling,
 * whether the model sees, which efforts it takes — goes back into the config as facts every role reads
 * without knowing the provider. Anything but a plain "no such model" — the network down, a 5xx — only
 * warns, since the deployment may well be right.
 */
suspend fun LlmProviderConfig.preflighted(http: HttpClient, codexAuth: CodexAuthStore?): LlmProviderConfig =
    when (this) {
        is LlmProviderConfig.OpenAi -> openAiPreflight(http, this)
        is LlmProviderConfig.Anthropic -> anthropicPreflight(http, this)
        is LlmProviderConfig.OpenAiCompatible -> compatiblePreflight(http, this)
        is LlmProviderConfig.Codex -> codexPreflight(http, this, codexAuth)
    }

/** The vendor's model object when it serves the configured model, `null` when it could not be asked, an error when it does not. */
internal suspend fun verifyModel(
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

    return runCatching { preflightJson.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
}

/** The entries of a server's `GET /models`, or `null` when it could not be asked or serves no such list. */
internal suspend fun listedModels(http: HttpClient, label: String, url: String, authorize: HttpRequestBuilder.() -> Unit): List<JsonObject>? {
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

    val data = runCatching { preflightJson.parseToJsonElement(response.bodyAsText()).jsonObject["data"] as? JsonArray }.getOrNull()

    if (data == null) log.warn { "$label: what its model list answered is not a list of models; keeping the assumptions" }

    return data?.filterIsInstance<JsonObject>()
}
