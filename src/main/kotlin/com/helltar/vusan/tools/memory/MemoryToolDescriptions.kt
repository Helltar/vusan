package com.helltar.vusan.tools.memory

internal object MemoryToolDescriptions {

    const val CLEAR_CHAT_HISTORY =
        "Wipes the stored chat history for this user (text turns only). " +
                """Use when the user explicitly asks to clear, forget, reset, or start fresh with the conversation, """ +
                """"clear our chat", "forget this conversation", "start fresh". """ +
                "After calling this, briefly confirm to the user that history is cleared."
}
