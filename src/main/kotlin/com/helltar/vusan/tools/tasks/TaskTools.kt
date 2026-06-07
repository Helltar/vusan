package com.helltar.vusan.tools.tasks

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tasks.*
import com.helltar.vusan.tools.suspendToolGuard
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val MAX_PROMPT_CHARS = 1000
private const val MAX_TITLE_CHARS = 120

private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

@Suppress("unused")
class TaskTools(
    private val repo: TasksRepository,
    private val context: RequestContext,
    private val maxTasksPerUser: Int
) : ToolSet {

    @Tool
    @LLMDescription(TaskToolDescriptions.SCHEDULE_TASK)
    suspend fun scheduleTask(
        @LLMDescription(TaskToolDescriptions.SCHEDULE_PROMPT)
        prompt: String,
        @LLMDescription(TaskToolDescriptions.SCHEDULE_SPEC)
        schedule: String,
        @LLMDescription(TaskToolDescriptions.SCHEDULE_TIMEZONE)
        timezone: String? = null,
        @LLMDescription(TaskToolDescriptions.SCHEDULE_TITLE)
        title: String? = null
    ): String = suspendToolGuard {
        val userId = context.userId

        check(userId != 0L) { "User ID is unavailable for task tools" }
        check(context.chatId != 0L) { "Chat ID is unavailable for task tools" }

        val trimmedPrompt = prompt.trim()

        require(trimmedPrompt.isNotEmpty()) { "Task prompt must not be empty" }

        require(trimmedPrompt.length <= MAX_PROMPT_CHARS) {
            "Task prompt must be at most $MAX_PROMPT_CHARS characters"
        }

        val trimmedTitle = title?.trim()?.takeIf { it.isNotEmpty() }

        require(trimmedTitle == null || trimmedTitle.length <= MAX_TITLE_CHARS) {
            "Task title must be at most $MAX_TITLE_CHARS characters"
        }

        val tz =
            parseTimezone(timezone)
                ?: return@suspendToolGuard "Unknown timezone=`$timezone`. Use IANA names like `Europe/Kyiv` or omit."

        val plan =
            when (val parsed = parseSchedule(schedule, Instant.now(), tz)) {
                is ScheduleParse.Err -> return@suspendToolGuard parsed.message
                is ScheduleParse.Ok -> parsed
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
                    recurrence = plan.recurrence,
                    timezone = tz,
                    nextFireAt = plan.firstFire,
                    creatorMessageId = context.messageId.takeIf { it > 0L },
                    creatorUsername = context.senderUsername,
                    creatorDisplayName = context.senderDisplayName,
                    chatIsPrivate = context.chatIsPrivate,
                    language = context.language
                )
            )

        "Scheduled task id=$id, fires=${formatFire(plan.firstFire, tz)} (${plan.recurrence.display})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.LIST_TASKS)
    suspend fun listTasks(): String = suspendToolGuard {
        val userId = context.userId

        check(userId != 0L) { "User ID is unavailable for task tools" }

        val tasks = repo.listActiveByUser(userId)

        if (tasks.isEmpty())
            return@suspendToolGuard "No active scheduled tasks."

        buildString {
            appendLine("Active scheduled tasks (${tasks.size}):")
            tasks.forEach { append(formatTaskLine(it)).append('\n') }
        }.trimEnd()
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.CANCEL_TASK)
    suspend fun cancelTask(
        @LLMDescription(TaskToolDescriptions.CANCEL_ID)
        id: Long
    ): String = suspendToolGuard {
        val userId = context.userId

        check(userId != 0L) { "User ID is unavailable for task tools" }

        val existing =
            repo.findForUser(userId, id)
                ?: return@suspendToolGuard "No scheduled task id=$id found for the current user."

        if (!existing.enabled)
            return@suspendToolGuard "Task id=$id is already cancelled."

        repo.delete(id)

        "Cancelled task id=$id (${formatFire(existing.nextFireAt, existing.timezone)}, ${existing.recurrence.display})."
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
        append(", repeat=").append(task.recurrence.display)
        task.title?.let { append(", title=\"").append(it).append('"') }
        append(", prompt=\"").append(task.prompt).append('"')
    }

private fun formatFire(instant: Instant, tz: ZoneId): String {
    val zoned = ZonedDateTime.ofInstant(instant, tz)
    return "${DISPLAY_FORMAT.format(zoned)} ${tz.id}"
}
