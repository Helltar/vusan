package com.helltar.vusan.agent.history

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
