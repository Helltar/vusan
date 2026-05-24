package com.helltar.vusan.tools.reminders

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.RequestContext
import com.helltar.vusan.reminders.NewReminder
import com.helltar.vusan.reminders.Recurrence
import com.helltar.vusan.reminders.Reminder
import com.helltar.vusan.reminders.RemindersRepository
import com.helltar.vusan.tools.common.suspendToolGuard
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val MAX_PROMPT_CHARS = 1000
private const val MAX_TITLE_CHARS = 120

private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]")
private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

@Suppress("unused")
class ReminderTools(
    private val repo: RemindersRepository,
    private val context: RequestContext,
    private val maxRemindersPerUser: Int,
) : ToolSet {

    @Tool
    @LLMDescription(ReminderToolDescriptions.SCHEDULE_REMINDER)
    suspend fun scheduleReminder(
        @LLMDescription(ReminderToolDescriptions.SCHEDULE_PROMPT)
        prompt: String,
        @LLMDescription(ReminderToolDescriptions.SCHEDULE_WHEN_LOCAL)
        whenLocal: String,
        @LLMDescription(ReminderToolDescriptions.SCHEDULE_REPEAT)
        repeat: String = "ONCE",
        @LLMDescription(ReminderToolDescriptions.SCHEDULE_TIMEZONE)
        timezone: String? = null,
        @LLMDescription(ReminderToolDescriptions.SCHEDULE_TITLE)
        title: String? = null,
    ): String = suspendToolGuard {
        val userId = context.userId
        check(userId != 0L) { "User ID is unavailable for reminder tools" }
        check(context.chatId != 0L) { "Chat ID is unavailable for reminder tools" }

        val trimmedPrompt = prompt.trim()
        require(trimmedPrompt.isNotEmpty()) { "Reminder prompt must not be empty" }
        require(trimmedPrompt.length <= MAX_PROMPT_CHARS) { "Reminder prompt must be at most $MAX_PROMPT_CHARS characters" }

        val trimmedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmedTitle == null || trimmedTitle.length <= MAX_TITLE_CHARS) {
            "Reminder title must be at most $MAX_TITLE_CHARS characters"
        }

        val recurrence = Recurrence.parse(repeat)
            ?: return@suspendToolGuard "Unknown repeat=`$repeat`. Use ONCE, DAILY, WEEKLY, or MONTHLY."

        val tz = parseTimezone(timezone)
            ?: return@suspendToolGuard "Unknown timezone=`$timezone`. Use IANA names like `Europe/Kyiv` or omit."

        val whenLocalParsed = runCatching { LocalDateTime.parse(whenLocal, LOCAL_DATE_TIME) }
            .getOrElse {
                return@suspendToolGuard "Cannot parse whenLocal=`$whenLocal`. Use ISO local datetime like `2026-05-25T09:00`."
            }

        val fireAt = whenLocalParsed.atZone(tz).toInstant()
        val now = Instant.now()
        if (!fireAt.isAfter(now)) {
            return@suspendToolGuard "whenLocal=`$whenLocal` ${tz.id} is in the past. Pick a future time."
        }

        val activeCount = repo.countActiveByUser(userId)
        if (activeCount >= maxRemindersPerUser) {
            return@suspendToolGuard "You already have $activeCount active reminders (limit $maxRemindersPerUser). " +
                "Cancel one with `cancelReminder` before scheduling a new one."
        }

        val id =
            repo.create(
                NewReminder(
                    userId = userId,
                    chatId = context.chatId,
                    prompt = trimmedPrompt,
                    title = trimmedTitle,
                    recurrence = recurrence,
                    timezone = tz,
                    nextFireAt = fireAt,
                    creatorMessageId = context.messageId.takeIf { it > 0L },
                    creatorUsername = context.senderUsername,
                    creatorDisplayName = context.senderDisplayName,
                    chatIsPrivate = context.chatIsPrivate,
                )
            )

        "Scheduled reminder id=$id, fires=${formatFire(fireAt, tz)} (${recurrence.name})."
    }

    @Tool
    @LLMDescription(ReminderToolDescriptions.LIST_REMINDERS)
    suspend fun listReminders(): String = suspendToolGuard {
        val userId = context.userId
        check(userId != 0L) { "User ID is unavailable for reminder tools" }

        val reminders = repo.listActiveByUser(userId)
        if (reminders.isEmpty()) return@suspendToolGuard "No active reminders."

        buildString {
            appendLine("Active reminders (${reminders.size}):")
            reminders.forEach { append(formatReminderLine(it)).append('\n') }
        }.trimEnd()
    }

    @Tool
    @LLMDescription(ReminderToolDescriptions.CANCEL_REMINDER)
    suspend fun cancelReminder(
        @LLMDescription(ReminderToolDescriptions.CANCEL_ID)
        id: Long,
    ): String = suspendToolGuard {
        val userId = context.userId
        check(userId != 0L) { "User ID is unavailable for reminder tools" }

        val existing = repo.findForUser(userId, id)
            ?: return@suspendToolGuard "No reminder id=$id found for the current user."

        if (!existing.enabled) return@suspendToolGuard "Reminder id=$id is already cancelled."

        repo.delete(id)
        "Cancelled reminder id=$id (${formatFire(existing.nextFireAt, existing.timezone)}, ${existing.recurrence.name})."
    }

    private fun parseTimezone(raw: String?): ZoneId? {
        if (raw.isNullOrBlank()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(raw.trim()) }.getOrNull()
    }
}

private fun formatReminderLine(r: Reminder): String =
    buildString {
        append("- id=").append(r.id)
        append(", fires=").append(formatFire(r.nextFireAt, r.timezone))
        append(", repeat=").append(r.recurrence.name)
        r.title?.let { append(", title=\"").append(it).append('"') }
        append(", prompt=\"").append(r.prompt).append('"')
    }

private fun formatFire(instant: Instant, tz: ZoneId): String {
    val zoned = ZonedDateTime.ofInstant(instant, tz)
    return "${DISPLAY_FORMAT.format(zoned)} ${tz.id}"
}
