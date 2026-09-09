package com.helltar.vusan.request

/**
 * Reads the chat facts a turn cannot get from the message that started it — and that a turn with no
 * message behind it has no other source for.
 *
 * Both callers need them *before* the agent runs, not after: a scheduled fire otherwise pays for a
 * whole agent run before finding out the chat refuses what it produced.
 */
fun interface ChatProfileLookup {

    suspend fun of(chat: ChatRef): ChatProfile
}
