package com.helltar.vusan.llm.openai

import com.helltar.vusan.llm.ssePayloadOrNull
import com.helltar.vusan.llm.STREAM_ERROR_STATUS
import com.helltar.vusan.llm.string
import com.helltar.vusan.llm.LlmException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Folds a Responses API event stream back into the single response object the non-streaming API returns.
 *
 * The final `response.completed` carries the envelope (status, model, usage), but the Codex backend leaves
 * its `output` empty, so items are collected from `response.output_item.done` and spliced back in: assistant
 * text, tool calls and the reasoning a tool loop must echo. An incomplete stream (output ceiling, content
 * filter) folds like a completed one with `incomplete_details` saying why; a failed or canceled stream is an
 * error, not an empty reply.
 */
class ResponsesStreamFolder(private val label: String) {

    private var envelope: JsonObject? = null
    private val output = mutableListOf<JsonElement>()

    fun accept(event: JsonObject) {
        when (event.string("type")) {
            "response.output_item.done" -> event["item"]?.let(output::add)
            "response.completed", "response.incomplete" -> envelope = event["response"] as? JsonObject
            "response.failed", "response.cancelled", "error" ->
                throw LlmException(label, STREAM_ERROR_STATUS, event.toString())
        }
    }

    /** The response the stream amounted to, or an error when it never completed. */
    fun response(): JsonObject {
        val response = envelope ?: throw LlmException(label, status = null, body = "stream ended without a completed response", cutShort = true)

        val completedOutput =
            output.takeIf { it.isNotEmpty() }?.let(::JsonArray)
                ?: (response["output"] as? JsonArray)
                ?: JsonArray(emptyList())

        return JsonObject(response + mapOf("output" to completedOutput))
    }
}

/** The response a whole event stream, read as lines, amounts to. */
fun collectStreamedResponse(lines: List<String>, label: String): JsonObject {
    val folder = ResponsesStreamFolder(label)

    for (line in lines) {
        line.ssePayloadOrNull()?.let(folder::accept)
    }

    return folder.response()
}
