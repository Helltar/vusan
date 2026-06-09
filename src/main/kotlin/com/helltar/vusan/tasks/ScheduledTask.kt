package com.helltar.vusan.tasks

import com.helltar.vusan.i18n.Language
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ScheduledTask(
    val id: Long,
    val userId: Long,
    val chatId: Long,
    val prompt: String,
    val title: String?,
    val recurrence: Recurrence,
    val timezone: ZoneId,
    val nextFireAt: Instant,
    val createdAt: Instant,
    val enabled: Boolean,
    val creatorMessageId: Long?,
    val creatorUsername: String?,
    val creatorDisplayName: String?,
    val chatIsPrivate: Boolean,
    val language: Language
)

data class NewScheduledTask(
    val userId: Long,
    val chatId: Long,
    val prompt: String,
    val title: String?,
    val recurrence: Recurrence,
    val timezone: ZoneId,
    val nextFireAt: Instant,
    val creatorMessageId: Long?,
    val creatorUsername: String?,
    val creatorDisplayName: String?,
    val chatIsPrivate: Boolean,
    val language: Language
)

private val FIRE_DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** user-facing fire time, e.g. `2026-05-30T09:00 Europe/Kyiv`. */
internal fun formatFire(instant: Instant, tz: ZoneId): String =
    "${FIRE_DISPLAY.format(ZonedDateTime.ofInstant(instant, tz))} ${tz.id}"
