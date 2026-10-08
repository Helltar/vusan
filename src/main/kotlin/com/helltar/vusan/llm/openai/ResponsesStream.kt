package com.helltar.vusan.llm.openai

import com.helltar.vusan.llm.LlmException
import com.helltar.vusan.llm.llmJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// a stream that ends in an error event answered 200 before it said anything went wrong
private const val STREAM_ERROR_STATUS = 200
private const val SSE_DATA_PREFIX = "data:"

/**
 * Folds a Responses API event stream back into the single response object the non-streaming API would
 * have returned.
 *
 * The final `response.completed` event carries the envelope — status, model, usage — but the Codex
 * backend leaves its `output` array empty, so the items are collected from `response.output_item.done`
 * as they arrive and spliced back in. Everything the agent depends on rides in those items: assistant
 * text, tool calls, and the reasoning items a tool loop has to echo back. A failed, incomplete or
 * cancelled stream is an error rather than an empty reply.
 */
class ResponsesStreamFolder(private val label: String) {

    private var envelope: JsonObject? = null
    private val output = mutableListOf<JsonElement>()

    fun accept(event: JsonObject) {
        when (event.string("type")) {
            "response.output_item.done" -> event["item"]?.let(output::add)
            "response.completed" -> envelope = event["response"] as? JsonObject
            "response.failed", "response.incomplete", "response.cancelled", "error" ->
                throw LlmException(label, STREAM_ERROR_STATUS, event.toString())
        }
    }

    /** The response the stream amounted to, or an error when it never completed. */
    fun response(): JsonObject {
        val response = envelope ?: throw LlmException(label, status = null, body = "stream ended without a completed response")

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
        if (!line.startsWith(SSE_DATA_PREFIX)) continue

        val payload = line.removePrefix(SSE_DATA_PREFIX).trim()
        if (payload.isEmpty() || payload == "[DONE]") continue

        runCatching { llmJson.parseToJsonElement(payload).jsonObject }.getOrNull()?.let(folder::accept)
    }

    return folder.response()
}
