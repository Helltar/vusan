package com.helltar.vusan.tools.memory

internal object MemoryToolDescriptions {

    const val CLEAR_CHAT_HISTORY =
        "Wipes this user's stored conversation history. " +
                "Does not affect scheduled tasks. " +
                """Use when the user explicitly asks to clear, forget, reset, or start fresh with the conversation, "clear our chat", "forget this conversation", "start fresh". """ +
                "After calling this, briefly confirm to the user that history is cleared."
}
