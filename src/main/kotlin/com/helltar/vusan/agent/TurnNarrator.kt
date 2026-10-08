package com.helltar.vusan.agent

/**
 * How a turn says something while it is still running. Everything a tool produces is queued in the
 * outbox and delivered once the turn is over, so work that takes minutes would otherwise announce what
 * it is about to do only after having done it. This is the way out: the words go to the chat through the
 * Telegram layer's live status for the turn, which is why nothing here names a chat or a message.
 *
 * [say] writes the plan into the live status, where the next thing said replaces it; [send] posts a
 * message of its own that stays. Either returns `false` when there is no live chat to write to — a
 * scheduled run has no one waiting on it — and the caller then queues the text the ordinary way instead.
 */
interface TurnNarrator {

    suspend fun say(text: String): Boolean

    suspend fun send(text: String): Boolean
}
