package com.helltar.vusan.tools.tasks

import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tasks.NewScheduledTask
import com.helltar.vusan.tasks.Recurrence
import java.time.Instant
import java.time.ZoneId

internal const val MAX_TASK_PROMPT_CHARS = 1000
internal const val MAX_TASK_TITLE_CHARS = 120

// a task remembers its creating turn (anchor message, topic, who asked), so both creating tools draft it from
// the context the same way.
internal fun RequestContext.newScheduledTask(
    scope: ConversationScope,
    prompt: String,
    title: String?,
    recurrence: Recurrence,
    timezone: ZoneId,
    nextFireAt: Instant,
    selfInitiated: Boolean,
    deliverPrivately: Boolean = false,
) = NewScheduledTask(
    scope = scope,
    prompt = prompt,
    title = title,
    recurrence = recurrence,
    timezone = timezone,
    nextFireAt = nextFireAt,
    creatorMessageId = messageId,
    creatorThreadId = chat.threadId,
    creatorUsername = sender.username,
    creatorDisplayName = sender.displayName,
    chatIsPrivate = chat.isPrivate,
    language = language,
    selfInitiated = selfInitiated,
    // a private chat is already where its owner reads; the flag would only say the same thing twice.
    deliverPrivately = deliverPrivately && !chat.isPrivate,
)

internal fun parseTimezone(raw: String?): ZoneId? {
    if (raw.isNullOrBlank()) return ZoneId.systemDefault()

    return runCatching { ZoneId.of(raw.trim()) }.getOrNull()
}
