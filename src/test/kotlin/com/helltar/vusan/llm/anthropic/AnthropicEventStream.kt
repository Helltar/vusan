package com.helltar.vusan.llm.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val FINAL_USAGE_FIELDS = setOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens", "output_tokens")

/**
 * Renders a Messages API reply the way the API streams it — the envelope first, each block opened and
 * grown by deltas, the stop reason and usage last — so a test over a mock engine hands the client a stream.
 */
internal fun messageEventStream(reply: String): String {
    val message = Json.parseToJsonElement(reply).jsonObject
    val usage = (message["usage"] as? JsonObject).orEmpty()
    val events = mutableListOf<JsonObject>()

    events +=
        buildJsonObject {
            put("type", "message_start")
            put("message", JsonObject(message + ("content" to JsonArray(emptyList())) + ("stop_reason" to JsonNull) + ("usage" to JsonObject(usage - "output_tokens"))))
        }

    (message["content"] as? JsonArray).orEmpty().map { it.jsonObject }.forEachIndexed { index, block ->
        val (opening, deltas) = block.streamed()

        events += buildJsonObject { put("type", "content_block_start"); put("index", index); put("content_block", opening) }
        deltas.forEach { delta -> events += buildJsonObject { put("type", "content_block_delta"); put("index", index); put("delta", delta) } }
        events += buildJsonObject { put("type", "content_block_stop"); put("index", index) }
    }

    events +=
        buildJsonObject {
            put("type", "message_delta")
            put(
                "delta",
                buildJsonObject {
                    message["stop_reason"]?.let { put("stop_reason", it) }
                    put("stop_sequence", JsonNull)
                    message["stop_details"]?.let { put("stop_details", it) }
                },
            )
            put("usage", JsonObject(usage.filterKeys { it in FINAL_USAGE_FIELDS }))
        }

    events += buildJsonObject { put("type", "message_stop") }

    return events.joinToString("") { "event: ${it.getValue("type").jsonPrimitive.content}\ndata: $it\n\n" }
}

// the block as it opens, and the deltas that grow it into the one given
private fun JsonObject.streamed(): Pair<JsonObject, List<JsonObject>> =
    when (this["type"]?.jsonPrimitive?.content) {
        "text" ->
            JsonObject(this + ("text" to JsonPrimitive(""))) to
                    listOf(buildJsonObject { put("type", "text_delta"); put("text", getValue("text")) })

        "thinking" ->
            JsonObject(this - "signature" + ("thinking" to JsonPrimitive(""))) to
                    listOfNotNull(
                        buildJsonObject { put("type", "thinking_delta"); put("thinking", this@streamed["thinking"] ?: JsonPrimitive("")) },
                        this["signature"]?.let { buildJsonObject { put("type", "signature_delta"); put("signature", it) } },
                    )

        "tool_use" ->
            JsonObject(this + ("input" to JsonObject(emptyMap()))) to
                    listOf(
                        buildJsonObject { put("type", "input_json_delta"); put("partial_json", "") },
                        buildJsonObject { put("type", "input_json_delta"); put("partial_json", getValue("input").toString()) },
                    )

        else -> this to emptyList()
    }
