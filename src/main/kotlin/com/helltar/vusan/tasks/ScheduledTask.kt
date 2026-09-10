package com.helltar.vusan.tasks

import com.helltar.vusan.delivery.Attribution
import com.helltar.vusan.delivery.AttributionReason
import com.helltar.vusan.delivery.Destination
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.request.ChatContext
import com.helltar.vusan.request.ChatProfile
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.SenderContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ScheduledTask(
    val id: Long,
    // whose task it is and where it fires, qualified: two platforms may issue the same ids, and a task
    // is both an owner's quota and somebody else's chat.
    val scope: ConversationScope,
    val prompt: String,
    val title: String?,
    val recurrence: Recurrence,
    val timezone: ZoneId,
    val nextFireAt: Instant,
    val createdAt: Instant,
    val paused: Boolean,
    val creatorMessageId: String?,
    // the forum topic the task was set up in. the creator message anchors a fire into the right topic
    // on its own, but the notices around it, and every fire after that message is gone, need this.
    val creatorThreadId: String?,
    val creatorUsername: String?,
    val creatorDisplayName: String?,
    val chatIsPrivate: Boolean,
    val language: Language,
    // the bot set this one up for itself as a follow-up, instead of the user asking for it.
    val selfInitiated: Boolean = false
)

data class NewScheduledTask(
    val scope: ConversationScope,
    val prompt: String,
    val title: String?,
    val recurrence: Recurrence,
    val timezone: ZoneId,
    val nextFireAt: Instant,
    val creatorMessageId: String?,
    val creatorThreadId: String?,
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
 * Where a fired task runs, rebuilt from what was stored when it was created.
 *
 * A task has no incoming message behind it, so there is no live chat flavor to read and no message to
 * answer. What survives is who set it up and where, which is what the turn needs to address the person
 * by name instead of nobody. The chat's description and what it lets the bot post are read fresh,
 * since neither was worth storing at the time.
 */
internal fun ScheduledTask.toRequestContext(profile: ChatProfile = ChatProfile.NONE): RequestContext =
    RequestContext(
        platform = scope.platform,
        chat =
            ChatContext(
                id = scope.chat.id,
                isPrivate = chatIsPrivate,
                // the turn runs in the topic the task was created in, so a follow-up it schedules is
                // anchored there too rather than in the forum's General.
                threadId = creatorThreadId,
                description = profile.description,
                capabilities = profile.capabilities
            ),
        sender =
            SenderContext(
                id = scope.user.id,
                displayName = creatorDisplayName,
                username = creatorUsername,
                languageCode = language.codes.firstOrNull()
            ),
        language = language
    )

/** Where this task's fire, and every notice about it, belongs. */
internal val ScheduledTask.destination: Destination
    get() = Destination(scope.chat, threadId = creatorThreadId)

/**
 * Whose fire this is, for the line the chat sees above it.
 *
 * A private chat gets none: its only other member is the person the task belongs to, and naming them
 * to themselves says nothing. What is passed on is who they are, not how to write them — the mention
 * is the messenger's own syntax.
 */
internal val ScheduledTask.attribution: Attribution?
    get() =
        if (chatIsPrivate)
            null
        else
            Attribution(
                anchorMessageId = creatorMessageId,
                person = scope.user,
                displayName = creatorDisplayName,
                username = creatorUsername,
                reason = if (selfInitiated) AttributionReason.FOLLOW_UP else AttributionReason.SCHEDULED
            )

/** Keeps a future slot, or advances a recurring task past every elapsed slot. */
internal fun ScheduledTask.nextFireAfterResume(now: Instant): Instant? =
    if (nextFireAt.isAfter(now))
        nextFireAt
    else
        recurrence.catchUpAfter(nextFireAt, timezone, now)
