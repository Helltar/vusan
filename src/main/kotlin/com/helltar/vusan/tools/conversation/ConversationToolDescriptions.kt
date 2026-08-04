package com.helltar.vusan.tools.conversation

internal object ConversationToolDescriptions {

    const val CLEAR_CONVERSATION =
        "Wipes this user's stored conversation history for the current chat. " +
                "Their history in other chats, everyone else's history, scheduled tasks and remembered memory all stay. " +
                """Use when the user explicitly asks to clear, forget, reset, or start fresh with the conversation, "clear our chat", "forget this conversation", "start fresh". """ +
                "After calling this, briefly confirm to the user that history is cleared."
}
