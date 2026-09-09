package com.helltar.vusan.tasks

import com.helltar.vusan.agent.MessageContext
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.telegram.ChatProfile
import com.helltar.vusan.telegram.delivery.ChatTarget
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
    val paused: Boolean,
    val creatorMessageId: Long?,
    // the forum topic the task was set up in. the creator message anchors a fire into the right topic
    // on its own, but the notices around it, and every fire after that message is gone, need this.
    val creatorThreadId: Int?,
    val creatorUsername: String?,
    val creatorDisplayName: String?,
    val chatIsPrivate: Boolean,
    val language: Language,
    // the bot set this one up for itself as a follow-up, instead of the user asking for it.
    val selfInitiated: Boolean = false
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
    val creatorThreadId: Int?,
    val creatorUsername: String?,
    val creatorDisplayName: String?,
    val chatIsPrivate: Boolean,
    val language: Language,
    val selfInitiated: Boolean = false
)

private val FIRE_DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** User-facing fire time, e.g. `2026-05-30T09:00 Europe/Kyiv`. */
internal fun formatFire(instant: Instant, tz: ZoneId): String =
    "${FIRE_DISPLAY.format(ZonedDateTime.ofInstant(instant, tz))} ${tz.id}"

/**
 * The Telegram metadata a fired task can still name. A task runs with no incoming message behind it,
 * so the live chat flavor is gone; what survives is who set it up and where, which is what the turn
 * needs to address the person by name instead of nobody. The chat's description and what it lets the
 * bot post are read fresh, since neither was worth storing at the time the task was created.
 */
internal fun ScheduledTask.toMessageContext(profile: ChatProfile = ChatProfile.NONE): MessageContext =
    MessageContext(
        chatId = chatId,
        chatType = if (chatIsPrivate) "private" else "group",
        isPrivate = chatIsPrivate,
        chatDescription = profile.description,
        userId = userId,
        userDisplayName = creatorDisplayName,
        userUsername = creatorUsername,
        userLanguageCode = language.codes.firstOrNull(),
        chatCapabilities = profile.capabilities
    )

/** Where this task's fire, and every notice about it, belongs. */
internal val ScheduledTask.chatTarget: ChatTarget
    get() = ChatTarget(chatId, creatorThreadId)

/** Keeps a future slot, or advances a recurring task past every elapsed slot. */
internal fun ScheduledTask.nextFireAfterResume(now: Instant): Instant? =
    if (nextFireAt.isAfter(now))
        nextFireAt
    else
        recurrence.catchUpAfter(nextFireAt, timezone, now)
