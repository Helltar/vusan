package com.helltar.vusan.telegram

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The messages the bot has taken a turn on, remembered only for as long as an edit to one of them
 * could still start another. That window is also what bounds the map: every write drops what has
 * aged out of it, so there is nothing to sweep separately.
 *
 * Deliberately per-process. After a restart an edit finds nothing here and starts no turn, which is
 * exactly what an edit did before any of this existed.
 */
internal class AnsweredMessages(private val window: Duration) {

    private val answeredAt = ConcurrentHashMap<String, Instant>()

    fun remember(chatId: Long, messageId: Long, now: Instant) {
        answeredAt.values.removeIf { it.hasAgedOut(now) }
        answeredAt[key(chatId, messageId)] = now
    }

    fun contains(chatId: Long, messageId: Long, now: Instant): Boolean =
        answeredAt[key(chatId, messageId)]?.hasAgedOut(now) == false

    private fun Instant.hasAgedOut(now: Instant): Boolean =
        now.isAfter(plusMillis(window.inWholeMilliseconds))

    private fun key(chatId: Long, messageId: Long): String = "$chatId:$messageId"
}
