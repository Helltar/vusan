package com.helltar.vusan.agent

/**
 * How a turn speaks while still running. Tool output is queued and delivered when the turn ends, so minutes
 * of work would otherwise announce its plan only after doing it. The words go out through the telegram
 * layer's live status, so nothing here names a chat or a message.
 *
 * [say] writes the plan into the live status, replaced by the next thing said; [send] posts a message that
 * stays. Both return `false` without a live chat (a scheduled run), and the caller then queues the text
 * normally.
 */
interface TurnNarrator {

    suspend fun say(text: String): Boolean

    suspend fun send(text: String): Boolean
}
