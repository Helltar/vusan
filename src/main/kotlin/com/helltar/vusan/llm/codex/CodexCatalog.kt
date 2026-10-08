package com.helltar.vusan.llm.codex

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.helltar.vusan.llm.ReasoningEffort

/** A model the signed-in ChatGPT account may actually run through Codex. */
data class CodexModel(
    val id: String,
    val displayName: String,
    val contextWindowTokens: Long?,
    val supportsVision: Boolean,
    val supportedReasoningEfforts: Set<ReasoningEffort>?,
    // the serving tiers this model offers beyond the standard one, by their request value. an empty set
    // means the catalog says none; `null` means it did not say.
    val supportedServiceTiers: Set<String>?,
    // `null` when the model takes no verbosity or the catalog names no default for it.
    val defaultVerbosity: String?,
)

@Serializable
private data class CodexModelsResponse(val models: List<CodexModelInfo> = emptyList())

@Serializable
private data class CodexModelInfo(
    val slug: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("context_window") val contextWindow: Long? = null,
    @SerialName("max_context_window") val maxContextWindow: Long? = null,
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
    @SerialName("supported_reasoning_efforts") val supportedReasoningEfforts: JsonElement? = null,
    @SerialName("supported_reasoning_levels") val supportedReasoningLevels: JsonElement? = null,
    @SerialName("service_tiers") val serviceTiers: List<CodexServiceTierInfo>? = null,
    @SerialName("support_verbosity") val supportVerbosity: Boolean = false,
    @SerialName("default_verbosity") val defaultVerbosity: String? = null,
)

@Serializable
private data class CodexServiceTierInfo(val id: String = "")

/**
 * The model catalog the signed-in subscription is entitled to.
 *
 * This is deliberately not the OpenAI Platform model list: the two overlap but are not the same set,
 * and picking a Platform-only model here fails at the first turn with an opaque backend error. Asking
 * the account what it can run is the only way to reject that at startup instead.
 */
suspend fun fetchCodexModels(http: HttpClient, auth: CodexAuthStore): List<CodexModel> {
    val credentials = auth.credentials()

    val response: CodexModelsResponse =
        http.get("$CODEX_BACKEND_BASE_URL/models") {
            // the endpoint 400s without it: "query.client_version: Field required"
            parameter("client_version", codexClientVersion())
            codexRequestHeaders(credentials).forEach { (name, value) -> header(name, value) }
        }.body()

    return response.models
        .filter { it.slug.isNotBlank() }
        .map {
            CodexModel(
                id = it.slug,
                displayName = it.displayName.ifBlank { it.slug },
                contextWindowTokens = it.contextWindow ?: it.maxContextWindow,
                // older catalogs omit modalities; those models predate this metadata and accepted images.
                supportsVision = it.inputModalities?.any { modality -> modality.equals("image", true) } ?: true,
                supportedReasoningEfforts =
                    (it.supportedReasoningEfforts ?: it.supportedReasoningLevels).reasoningEfforts(),
                supportedServiceTiers =
                    it.serviceTiers?.mapNotNull { tier -> tier.id.takeIf(String::isNotBlank) }?.toSet(),
                defaultVerbosity = it.defaultVerbosity?.takeIf { value -> it.supportVerbosity && value.isNotBlank() },
            )
        }
}

private fun JsonElement?.reasoningEfforts(): Set<ReasoningEffort>? {
    val values = this as? JsonArray ?: return null
    val efforts =
        values.mapNotNull { value ->
            val raw =
                when (value) {
                    is JsonPrimitive -> value.contentOrNull
                    is JsonObject ->
                        listOf("reasoning_effort", "reasoningEffort", "effort", "level")
                            .firstNotNullOfOrNull { key -> value[key]?.jsonPrimitive?.contentOrNull }
                    else -> null
                }

            raw?.let { runCatching { ReasoningEffort.valueOf(it.trim().uppercase()) }.getOrNull() }
        }.toSet()

    // an unfamiliar non-empty shape is metadata drift, so skip validation instead of rejecting a
    // working model. an explicit empty array still means that no selectable effort is supported.
    return efforts.takeIf { it.isNotEmpty() || values.isEmpty() }
}
