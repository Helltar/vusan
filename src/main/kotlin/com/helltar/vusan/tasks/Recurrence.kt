package com.helltar.vusan.tasks

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

sealed interface Recurrence {

    companion object {
        val MIN_INTERVAL = 5.minutes

        private val CRON_PARSER = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
        private val H_M_REGEX = Regex("""(?:(\d+)\s*h)?\s*(?:(\d+)\s*m(?:in)?)?""", RegexOption.IGNORE_CASE)

        fun parse(raw: String): Recurrence? {
            val token = raw.trim()

            if (token.equals("once", ignoreCase = true))
                return Once

            val head = token.substringBefore(':', "").lowercase()
            val tail = token.substringAfter(':', "")

            return when (head) {
                "every" -> parseInterval(tail)?.let { runCatching { Every(it) }.getOrNull() }
                "cron" -> runCatching { Cron(tail) }.getOrNull()
                else -> null
            }
        }

        internal fun parseInterval(text: String): Duration? {
            val trimmed = text.trim()

            if (trimmed.isEmpty())
                return null

            runCatching { Duration.parse(trimmed) }.getOrNull()?.let { return it }

            val match = H_M_REGEX.matchEntire(trimmed) ?: return null
            val hours = match.groupValues[1].toLongOrNull() ?: 0
            val minutes = match.groupValues[2].toLongOrNull() ?: 0

            return (hours.hours + minutes.minutes).takeIf { it > Duration.ZERO }
        }

        internal fun validateCron(expression: String): Result<Unit> =
            runCatching { CRON_PARSER.parse(expression).validate() }
    }

    val display: String

    fun nextAfter(instant: Instant, timezone: ZoneId): Instant?

    fun serialize(): String

    fun catchUpAfter(current: Instant, timezone: ZoneId, now: Instant): Instant? {
        var next = nextAfter(current, timezone) ?: return null

        while (!next.isAfter(now)) {
            next = nextAfter(next, timezone) ?: return null
        }

        return next
    }

    data object Once : Recurrence {
        override val display get() = "once"
        override fun nextAfter(instant: Instant, timezone: ZoneId): Instant? = null
        override fun serialize() = "once"
    }

    data class Every(val interval: Duration) : Recurrence {
        init {
            require(interval >= MIN_INTERVAL) { "interval must be at least $MIN_INTERVAL, was $interval" }
        }

        override val display get() = "every $interval"
        override fun serialize() = "every:${interval.toIsoString()}"
        override fun nextAfter(instant: Instant, timezone: ZoneId): Instant = instant.plus(interval.toJavaDuration())
    }

    data class Cron(val expression: String) : Recurrence {
        private val executionTime = ExecutionTime.forCron(CRON_PARSER.parse(expression).validate())

        override val display get() = "cron `$expression`"
        override fun serialize() = "cron:$expression"

        override fun nextAfter(instant: Instant, timezone: ZoneId): Instant? =
            executionTime.nextExecution(instant.atZone(timezone)).map { it.toInstant() }.orElse(null)
    }
}

sealed interface ScheduleParse {
    data class Ok(val recurrence: Recurrence, val firstFire: Instant) : ScheduleParse
    data class Err(val message: String) : ScheduleParse
}

fun parseSchedule(raw: String, now: Instant, timezone: ZoneId): ScheduleParse {
    val trimmed = raw.trim()
    val keyword = trimmed.substringBefore(' ', trimmed).lowercase()
    val rest = trimmed.substringAfter(' ', "").trim()

    return when (keyword) {
        "once" -> parseOnce(rest, now, timezone)
        "every" -> parseEvery(rest, now)
        "cron" -> parseCron(rest, now, timezone)
        else -> ScheduleParse.Err(
            "Unknown schedule=`$raw`. Use `once <ISO datetime>` (e.g. `once 2026-05-30T09:00`), " +
                    "`every <interval>` (e.g. `every 90m`, `every 2h`), " +
                    "or `cron <expr>` (UNIX 5-field, e.g. `cron 0 18 * * 1-5`)."
        )
    }
}

private val SCHEDULE_LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]")

private fun parseOnce(rest: String, now: Instant, timezone: ZoneId): ScheduleParse {
    val local =
        runCatching { LocalDateTime.parse(rest, SCHEDULE_LOCAL_DATE_TIME) }.getOrNull()
            ?: return ScheduleParse.Err("Cannot parse once datetime=`$rest`. Use ISO local datetime like `2026-05-30T09:00`.")

    val fireAt = local.atZone(timezone).toInstant()

    if (!fireAt.isAfter(now))
        return ScheduleParse.Err("once datetime=`$rest` ${timezone.id} is in the past. Pick a future time.")

    return ScheduleParse.Ok(Recurrence.Once, fireAt)
}

private fun parseEvery(rest: String, now: Instant): ScheduleParse {
    val interval =
        Recurrence.parseInterval(rest)
            ?: return ScheduleParse.Err("Cannot parse interval=`$rest`. Use forms like `90m`, `2h`, `1h30m`.")

    if (interval < Recurrence.MIN_INTERVAL)
        return ScheduleParse.Err("Interval `$rest` is below the ${Recurrence.MIN_INTERVAL} minimum.")

    return ScheduleParse.Ok(Recurrence.Every(interval), now.plus(interval.toJavaDuration()))
}

private fun parseCron(rest: String, now: Instant, timezone: ZoneId): ScheduleParse {
    if (Recurrence.validateCron(rest).isFailure) {
        return ScheduleParse.Err(
            "Invalid cron=`$rest`. Use UNIX 5-field `minute hour day-of-month month day-of-week`, " +
                    "e.g. `0 9 * * *` (daily 09:00), `0 18 * * 1-5` (weekdays 18:00), `0 0 1,15 * *` (1st & 15th)."
        )
    }

    val cron = Recurrence.Cron(rest)

    val firstFire =
        cron.nextAfter(now, timezone)
            ?: return ScheduleParse.Err("cron=`$rest` has no upcoming fire time.")

    return ScheduleParse.Ok(cron, firstFire)
}
