package com.helltar.vusan.telegram

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * The messages the bot has taken a turn on, so that it never takes a second turn on the same one.
 * Retention is also what bounds the map: every write drops what has aged out of it, so there is nothing
 * to sweep separately.
 *
 * Deliberately per-process. After a restart nothing here is known, which is the position the bot was in
 * before any of this existed.
 */
internal class AnsweredMessages(private val retention: Duration) {

    private val answeredAt = ConcurrentHashMap<String, Instant>()

    /**
     * Claims a message for a turn, reporting whether this is the first one. `false` means the bot has
     * already answered it and this delivery is a duplicate.
     */
    fun markAnswered(chatId: Long, messageId: Long, now: Instant): Boolean {
        answeredAt.values.removeIf { it.hasAgedOut(now) }
        return answeredAt.putIfAbsent(key(chatId, messageId), now) == null
    }

    private fun Instant.hasAgedOut(now: Instant): Boolean =
        now.isAfter(plusMillis(retention.inWholeMilliseconds))

    private fun key(chatId: Long, messageId: Long): String = "$chatId:$messageId"
}
