package com.helltar.vusan.reminders

import java.time.Instant
import java.time.ZoneId

data class Reminder(
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
)

data class NewReminder(
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
)
