package com.helltar.vusan.tasks

import java.time.Instant
import java.time.ZoneId

enum class Recurrence {
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY;

    fun advance(current: Instant, timezone: ZoneId): Instant? {
        val local = current.atZone(timezone)
        return when (this) {
            ONCE -> null
            DAILY -> local.plusDays(1).toInstant()
            WEEKLY -> local.plusWeeks(1).toInstant()
            MONTHLY -> local.plusMonths(1).toInstant()
        }
    }

    fun catchUpAfter(current: Instant, timezone: ZoneId, now: Instant): Instant? {
        if (this == ONCE) return null
        var next = advance(current, timezone) ?: return null
        while (!next.isAfter(now)) {
            next = advance(next, timezone) ?: return null
        }
        return next
    }

    companion object {
        fun parse(raw: String): Recurrence? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}
