package com.helltar.vusan.llm.anthropic

import com.helltar.vusan.llm.LlmException
import com.helltar.vusan.llm.llmJson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.TreeMap

/**
 * Folds a Messages API event stream back into the message object the non-streaming API would have
 * returned, so a reply is read the same way whichever way it arrives.
 *
 * `message_start` carries the envelope with an empty `content`; each content block opens with
 * `content_block_start`, grows by deltas — text, thinking and then its signature, a tool call's input as
 * fragments of JSON — and closes with `content_block_stop`; `message_delta` brings the stop reason and the
 * final usage, which replace the envelope's field by field; `message_stop` ends it. An `error` event is
 * the API failing after it answered 200, and a stream that ends before `message_stop` was cut short,
 * which the retry reads as a reason to call again.
 */
class MessagesStreamFolder(private val label: String) {

    private var envelope: JsonObject? = null
    private var stopped = false
    private val blocks = TreeMap<Int, MutableMap<String, JsonElement>>()
    private val partialInputs = mutableMapOf<Int, StringBuilder>()

    fun accept(event: JsonObject) {
        when (event.string("type")) {
            "message_start" -> envelope = event["message"] as? JsonObject
            "content_block_start" -> (event["content_block"] as? JsonObject)?.let { blocks[event.index] = it.toMutableMap() }
            "content_block_delta" -> (event["delta"] as? JsonObject)?.let { applyDelta(event.index, it) }
            "content_block_stop" -> closeInput(event.index)
            "message_delta" -> envelope = envelope?.let { merge(it, event) }
            "message_stop" -> stopped = true
            "error" -> throw LlmException(label, STREAM_ERROR_STATUS, event.toString())
        }
    }

    /** The message the stream amounted to, or an error when it never finished. */
    fun response(): JsonObject {
        val response = envelope?.takeIf { stopped } ?: throw LlmException(label, status = null, body = "stream ended without a completed response")

        return JsonObject(response + ("content" to JsonArray(blocks.values.map(::JsonObject))))
    }

    private fun applyDelta(index: Int, delta: JsonObject) {
        val block = blocks[index] ?: return

        when (delta.string("type")) {
            "text_delta" -> block.append("text", delta.string("text"))
            "thinking_delta" -> block.append("thinking", delta.string("thinking"))
            "signature_delta" -> delta["signature"]?.let { block["signature"] = it }
            "input_json_delta" -> partialInputs.getOrPut(index, ::StringBuilder).append(delta.string("partial_json").orEmpty())
        }
    }

    // a tool call's input arrives as fragments of json; nothing at all is the empty object the block opened
    // with, and a garbled one becomes that too, with the agent's guard telling the model what was missing
    private fun closeInput(index: Int) {
        val raw = partialInputs.remove(index)?.toString()?.takeIf { it.isNotBlank() } ?: return
        val block = blocks[index] ?: return

        block["input"] =
            runCatching { llmJson.parseToJsonElement(raw).jsonObject }
                .getOrElse {
                    log.warn { "$label: a tool call's input did not fold into a json object: ${raw.take(INPUT_PREVIEW_CHARS)}" }
                    JsonObject(emptyMap())
                }
    }

    // the stop reason and the final usage arrive last and replace what the envelope opened with, field by
    // field, so the cache figures only the envelope states survive
    private fun merge(envelope: JsonObject, event: JsonObject): JsonObject {
        val delta = (event["delta"] as? JsonObject).orEmpty()
        val usage = (envelope["usage"] as? JsonObject).orEmpty() + (event["usage"] as? JsonObject).orEmpty()

        return JsonObject(envelope + delta + ("usage" to JsonObject(usage)))
    }

    private companion object {
        // a stream that ends in an error event answered 200 before it said anything went wrong
        const val STREAM_ERROR_STATUS = 200
        const val INPUT_PREVIEW_CHARS = 200

        val log = KotlinLogging.logger {}
    }
}

private fun MutableMap<String, JsonElement>.append(field: String, text: String?) {
    if (text == null) return

    this[field] = JsonPrimitive((this[field] as? JsonPrimitive)?.contentOrNull.orEmpty() + text)
}

private val JsonObject.index: Int
    get() = (this["index"] as? JsonPrimitive)?.intOrNull ?: 0
