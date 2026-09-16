package com.helltar.vusan.tools.tasks

import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tasks.NewScheduledTask
import com.helltar.vusan.tasks.Recurrence
import java.time.Instant
import java.time.ZoneId

internal const val MAX_TASK_PROMPT_CHARS = 1000
internal const val MAX_TASK_TITLE_CHARS = 120

// a task remembers the turn that created it — the message to anchor to, the topic, who asked — so
// the two tools that create one draft it from the same context the same way.
internal fun RequestContext.newScheduledTask(
    scope: ConversationScope,
    prompt: String,
    title: String?,
    recurrence: Recurrence,
    timezone: ZoneId,
    nextFireAt: Instant,
    selfInitiated: Boolean,
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
)

internal fun parseTimezone(raw: String?): ZoneId? {
    if (raw.isNullOrBlank()) return ZoneId.systemDefault()

    return runCatching { ZoneId.of(raw.trim()) }.getOrNull()
}
