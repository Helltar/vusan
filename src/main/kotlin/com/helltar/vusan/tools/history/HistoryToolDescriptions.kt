package com.helltar.vusan.tools.history

internal object HistoryToolDescriptions {

    const val CLEAR_CHAT_HISTORY =
        "Wipes this user's stored conversation history. " +
                "Does not affect scheduled tasks or remembered memory. " +
                """Use when the user explicitly asks to clear, forget, reset, or start fresh with the conversation, "clear our chat", "forget this conversation", "start fresh". """ +
                "After calling this, briefly confirm to the user that history is cleared."
}
