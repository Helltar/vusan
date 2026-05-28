package com.helltar.vusan.tools.tasks

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.RequestContext
import com.helltar.vusan.tasks.NewScheduledTask
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tasks.ScheduledTask
import com.helltar.vusan.tasks.TasksRepository
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
class TaskTools(
    private val repo: TasksRepository,
    private val context: RequestContext,
    private val maxTasksPerUser: Int,
) : ToolSet {

    @Tool
    @LLMDescription(TaskToolDescriptions.SCHEDULE_TASK)
    suspend fun scheduleTask(
        @LLMDescription(TaskToolDescriptions.SCHEDULE_PROMPT)
        prompt: String,
        @LLMDescription(TaskToolDescriptions.SCHEDULE_WHEN_LOCAL)
        whenLocal: String,
        @LLMDescription(TaskToolDescriptions.SCHEDULE_REPEAT)
        repeat: String = "ONCE",
        @LLMDescription(TaskToolDescriptions.SCHEDULE_TIMEZONE)
        timezone: String? = null,
        @LLMDescription(TaskToolDescriptions.SCHEDULE_TITLE)
        title: String? = null,
    ): String = suspendToolGuard {
        val userId = context.userId
        check(userId != 0L) { "User ID is unavailable for task tools" }
        check(context.chatId != 0L) { "Chat ID is unavailable for task tools" }

        val trimmedPrompt = prompt.trim()
        require(trimmedPrompt.isNotEmpty()) { "Task prompt must not be empty" }
        require(trimmedPrompt.length <= MAX_PROMPT_CHARS) { "Task prompt must be at most $MAX_PROMPT_CHARS characters" }

        val trimmedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        require(trimmedTitle == null || trimmedTitle.length <= MAX_TITLE_CHARS) {
            "Task title must be at most $MAX_TITLE_CHARS characters"
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
        if (activeCount >= maxTasksPerUser) {
            return@suspendToolGuard "You already have $activeCount active tasks (limit $maxTasksPerUser). " +
                "Cancel one with `cancelTask` before scheduling a new one."
        }

        val id =
            repo.create(
                NewScheduledTask(
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

        "Scheduled task id=$id, fires=${formatFire(fireAt, tz)} (${recurrence.name})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.LIST_TASKS)
    suspend fun listTasks(): String = suspendToolGuard {
        val userId = context.userId
        check(userId != 0L) { "User ID is unavailable for task tools" }

        val tasks = repo.listActiveByUser(userId)
        if (tasks.isEmpty()) return@suspendToolGuard "No active scheduled tasks."

        buildString {
            appendLine("Active scheduled tasks (${tasks.size}):")
            tasks.forEach { append(formatTaskLine(it)).append('\n') }
        }.trimEnd()
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.CANCEL_TASK)
    suspend fun cancelTask(
        @LLMDescription(TaskToolDescriptions.CANCEL_ID)
        id: Long,
    ): String = suspendToolGuard {
        val userId = context.userId
        check(userId != 0L) { "User ID is unavailable for task tools" }

        val existing = repo.findForUser(userId, id)
            ?: return@suspendToolGuard "No scheduled task id=$id found for the current user."

        if (!existing.enabled) return@suspendToolGuard "Task id=$id is already cancelled."

        repo.delete(id)
        "Cancelled task id=$id (${formatFire(existing.nextFireAt, existing.timezone)}, ${existing.recurrence.name})."
    }

    private fun parseTimezone(raw: String?): ZoneId? {
        if (raw.isNullOrBlank()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(raw.trim()) }.getOrNull()
    }
}

private fun formatTaskLine(task: ScheduledTask): String =
    buildString {
        append("- id=").append(task.id)
        append(", fires=").append(formatFire(task.nextFireAt, task.timezone))
        append(", repeat=").append(task.recurrence.name)
        task.title?.let { append(", title=\"").append(it).append('"') }
        append(", prompt=\"").append(task.prompt).append('"')
    }

private fun formatFire(instant: Instant, tz: ZoneId): String {
    val zoned = ZonedDateTime.ofInstant(instant, tz)
    return "${DISPLAY_FORMAT.format(zoned)} ${tz.id}"
}
