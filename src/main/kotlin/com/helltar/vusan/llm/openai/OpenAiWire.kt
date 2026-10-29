package com.helltar.vusan.llm.openai

import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.llmJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/** Which of OpenAI's two HTTP APIs a server is spoken to through: `/chat/completions` or `/responses`. */
enum class OpenAiEndpoint {
    COMPLETIONS,
    RESPONSES
}

private const val ARGS_PREVIEW_CHARS = 200

private val log = KotlinLogging.logger {}

/** The bearer header for [apiKey], none when the HTTP client signs its requests itself, as the Codex one does. */
internal fun bearerHeaders(apiKey: String?): Map<String, String> = apiKey?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

// the arguments arrive as a json string; a model that garbles it still gets its call answered, with the
// empty-args guard in the agent telling it what was missing.
internal fun parseToolArguments(raw: String?, label: String): JsonObject {
    if (raw.isNullOrBlank()) return JsonObject(emptyMap())

    return runCatching { llmJson.parseToJsonElement(raw).jsonObject }
        .getOrElse {
            log.warn { "$label: a tool call's arguments were not a json object: ${raw.take(ARGS_PREVIEW_CHARS)}" }
            JsonObject(emptyMap())
        }
}

internal fun Part.Image.dataUrl(): String = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"
