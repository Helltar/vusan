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
    // whose task and where it fires, qualified: two platforms may issue the same ids, and a task is both an
    // owner's quota and someone's chat.
    val scope: ConversationScope,
    val prompt: String,
    val title: String?,
    val recurrence: Recurrence,
    val timezone: ZoneId,
    val nextFireAt: Instant,
    val paused: Boolean,
    val creatorMessageId: String?,
    // the forum topic the task was set up in. the creator message anchors a fire into its topic, but the
    // notices around it, and fires after that message is gone, need this.
    val creatorThreadId: String?,
    val creatorUsername: String?,
    val creatorDisplayName: String?,
    val chatIsPrivate: Boolean,
    val language: Language,
    // a follow-up the bot set up itself, not one the user asked for.
    val selfInitiated: Boolean = false,
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
    val selfInitiated: Boolean = false,
)

private val FIRE_DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** User-facing fire time, e.g. `2026-05-30T09:00 Europe/Kyiv`. */
internal fun formatFire(instant: Instant, tz: ZoneId): String =
    "${FIRE_DISPLAY.format(ZonedDateTime.ofInstant(instant, tz))} ${tz.id}"

/**
 * Where a fired task runs, rebuilt from what was stored at creation. With no incoming message there is no
 * live chat kind and nothing to answer; what survives is who set it up and where, so the turn can address the
 * person by name. The chat's description and permissions are read fresh.
 */
internal fun ScheduledTask.toRequestContext(profile: ChatProfile = ChatProfile.NONE): RequestContext =
    RequestContext(
        platform = scope.platform,
        chat =
            ChatContext(
                id = scope.chat.id,
                isPrivate = chatIsPrivate,
                // the turn runs in the task's topic, so a follow-up it schedules is anchored there too, not
                // in the forum's General.
                threadId = creatorThreadId,
                description = profile.description,
                capabilities = profile.capabilities,
            ),
        sender =
            SenderContext(
                id = scope.user.id,
                displayName = creatorDisplayName,
                username = creatorUsername,
                languageCode = language.code,
            ),
        language = language,
    )

/** Where this task's fire, and every notice about it, belongs. */
internal val ScheduledTask.destination: Destination
    get() = Destination(scope.chat, threadId = creatorThreadId)

/**
 * Whose fire this is, for the line above it in the chat. A private chat gets none: naming the owner to
 * themselves says nothing. Who they are is passed on, not how to write them; the mention is the messenger's
 * syntax.
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
                reason = if (selfInitiated) AttributionReason.FOLLOW_UP else AttributionReason.SCHEDULED,
            )

/** Keeps a future slot, or advances a recurring task past every elapsed slot. */
internal fun ScheduledTask.nextFireAfterResume(now: Instant): Instant? =
    if (nextFireAt.isAfter(now))
        nextFireAt
    else
        recurrence.catchUpAfter(nextFireAt, timezone, now)
