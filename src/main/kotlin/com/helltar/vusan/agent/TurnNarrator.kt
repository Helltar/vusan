package com.helltar.vusan.agent

/**
 * How a turn says something while it is still running. Everything a tool produces is queued in the
 * outbox and delivered once the turn is over, so work that takes minutes would otherwise announce what
 * it is about to do only after having done it. This is the way out: the words go to the live status the
 * Telegram layer keeps for the turn, which is why nothing here names a chat or a message.
 *
 * [say] returns `false` when there is no live status to write to — a scheduled run has no one waiting on
 * it — and the caller then queues the text the ordinary way instead.
 */
interface TurnNarrator {
    suspend fun say(text: String): Boolean
}
