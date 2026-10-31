package com.helltar.vusan.request

/**
 * Reads the chat facts a turn cannot get from its message, and that a turn without one has no other source
 * for. Both callers need them *before* the agent runs: otherwise a scheduled fire pays for a whole run before
 * learning the chat refuses its output.
 */
fun interface ChatProfileLookup {

    suspend fun of(chat: ChatRef): ChatProfile
}
