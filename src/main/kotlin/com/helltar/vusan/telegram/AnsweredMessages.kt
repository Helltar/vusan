package com.helltar.vusan.telegram

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The messages the bot took a turn on, so it never takes a second. Every write drops what aged out, so
 * retention also bounds the map.
 *
 * Per process on purpose: after a restart nothing is known, as before this existed.
 */
internal class AnsweredMessages(private val retention: Duration) {

    private val answeredAt = ConcurrentHashMap<String, Instant>()

    /**
     * Claims a message for a turn, reporting whether this is the first claim; `false` means a duplicate
     * delivery.
     */
    fun markAnswered(chatId: Long, messageId: Long, now: Instant): Boolean {
        answeredAt.values.removeIf { it.hasAgedOut(now) }

        return answeredAt.putIfAbsent(key(chatId, messageId), now) == null
    }

    private fun Instant.hasAgedOut(now: Instant): Boolean =
        now.isAfter(plusMillis(retention.inWholeMilliseconds))

    private fun key(chatId: Long, messageId: Long): String = "$chatId:$messageId"
}
