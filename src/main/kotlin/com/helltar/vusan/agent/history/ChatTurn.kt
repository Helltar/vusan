package com.helltar.vusan.agent.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

enum class ChatRole { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT }

data class ChatTurn(
    val role: ChatRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolIsError: Boolean? = null
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
private const val TRUNCATION_MARKER = "… [truncated]"

fun toolCallArgsForHistory(rawArgs: String): String {
    val obj = runCatching { Json.parseToJsonElement(rawArgs).jsonObject }.getOrNull() ?: return "{}"

    val bounded =
        JsonObject(
            obj.mapValues { (_, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

                if (text != null && text.length > TOOL_CALL_ARG_VALUE_MAX_CHARS)
                    JsonPrimitive(text.take(TOOL_CALL_ARG_VALUE_MAX_CHARS) + TRUNCATION_MARKER)
                else
                    value
            }
        )

    return bounded.toString()
}
