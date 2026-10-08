package com.helltar.vusan.agent.conversation

import com.helltar.vusan.common.limitTo
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.ToolResult
import com.helltar.vusan.llm.llmJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class ChatRole { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT }

data class ChatTurn(
    val role: ChatRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolIsError: Boolean? = null,
) {

    init {
        when (role) {
            ChatRole.TOOL_CALL ->
                require(!toolCallId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                    "TOOL_CALL turn requires non-blank toolCallId and toolName"
                }

            ChatRole.TOOL_RESULT ->
                require(!toolCallId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                    "TOOL_RESULT turn requires non-blank toolCallId and toolName"
                }

            ChatRole.USER, ChatRole.ASSISTANT -> Unit
        }
    }
}

private const val TOOL_CALL_ARG_VALUE_MAX_CHARS = 2_000
private const val TOOL_CALL_ARGS_MAX_CHARS = 4_000
private const val TRUNCATION_MARKER = "… [truncated]"

fun toolCallArgsForStorage(rawArgs: String): String {
    val obj = runCatching { Json.parseToJsonElement(rawArgs).jsonObject }.getOrNull() ?: return "{}"

    val bounded =
        JsonObject(
            obj.mapValues { (_, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

                if (text != null && text.length > TOOL_CALL_ARG_VALUE_MAX_CHARS)
                    JsonPrimitive(text.take(TOOL_CALL_ARG_VALUE_MAX_CHARS) + TRUNCATION_MARKER)
                else
                    value
            },
        )

    val serialized = bounded.toString()
    if (serialized.length <= TOOL_CALL_ARGS_MAX_CHARS) return serialized

    for (valueLimit in listOf(1_000, 500, 200, 80, 20)) {
        val compact =
            JsonObject(
                obj.mapValues { (_, value) ->
                    when (value) {
                        is JsonPrimitive ->
                            if (value.isString)
                                JsonPrimitive(value.content.limitTo(valueLimit))
                            else
                                value

                        else -> JsonPrimitive(value.toString().limitTo(valueLimit))
                    }
                },
            ).toString()

        if (compact.length <= TOOL_CALL_ARGS_MAX_CHARS) return compact
    }

    val selected = linkedMapOf<String, JsonElement>()

    for ((key, value) in obj) {
        val compactValue = JsonPrimitive(value.toString().limitTo(20))
        val candidate = JsonObject(selected + (key to compactValue))
        if (candidate.toString().length > TOOL_CALL_ARGS_MAX_CHARS) break
        selected[key] = compactValue
    }

    return JsonObject(selected).toString()
}

/**
 * Stored turns as the messages a request carries. Tool calls of one batch share the assistant message
 * that made them, and their results share the one message that answers them, the way both APIs want it.
 */
internal fun List<ChatTurn>.toMessages(): List<Message> =
    buildList {
        val pendingResults = mutableListOf<ToolResult>()

        fun flushResults() {
            if (pendingResults.isNotEmpty()) add(Message.ToolResults(pendingResults.toList()))
            pendingResults.clear()
        }

        for (turn in this@toMessages) {
            when (turn.role) {
                ChatRole.USER -> {
                    flushResults()
                    add(Message.User(turn.content))
                }

                ChatRole.ASSISTANT -> {
                    flushResults()
                    add(Message.Assistant(turn.content))
                }

                ChatRole.TOOL_CALL -> {
                    val call =
                        Part.ToolCall(
                            id = checkNotNull(turn.toolCallId) { "TOOL_CALL row without toolCallId" },
                            name = checkNotNull(turn.toolName) { "TOOL_CALL row without toolName" },
                            arguments = storedToolArgs(turn.content),
                        )

                    val previous = lastOrNull() as? Message.Assistant

                    if (pendingResults.isEmpty() && previous != null && previous.parts.all { it is Part.ToolCall }) {
                        set(lastIndex, Message.Assistant(previous.parts + call))
                    } else {
                        flushResults()
                        add(Message.Assistant(listOf(call)))
                    }
                }

                ChatRole.TOOL_RESULT ->
                    pendingResults +=
                        ToolResult(
                            callId = checkNotNull(turn.toolCallId) { "TOOL_RESULT row without toolCallId" },
                            name = checkNotNull(turn.toolName) { "TOOL_RESULT row without toolName" },
                            output = turn.content,
                            isError = turn.toolIsError ?: false,
                        )
            }
        }

        flushResults()
    }

// the stored arguments are the json the model sent, bounded; whatever does not read as an object replays as none
private fun storedToolArgs(content: String): JsonObject =
    runCatching { llmJson.parseToJsonElement(toolCallArgsForStorage(content)).jsonObject }.getOrDefault(JsonObject(emptyMap()))
