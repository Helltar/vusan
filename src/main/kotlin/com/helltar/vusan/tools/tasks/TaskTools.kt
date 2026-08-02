package com.helltar.vusan.tools.tasks

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.requireChatId
import com.helltar.vusan.request.requireUserId
import com.helltar.vusan.tasks.*
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.time.Instant
import java.time.ZoneId

private const val MAX_PROMPT_CHARS = 1000
private const val MAX_TITLE_CHARS = 120

@Suppress("unused")
class TaskTools(
    private val repo: TasksRepository,
    private val context: RequestContext,
    private val maxTasksPerUser: Int,
    private val maxFollowUpsPerUser: Int
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
        val userId = context.requireUserId()
        val chatId = context.requireChatId()

        val trimmedPrompt = prompt.requireToolText("Task prompt", MAX_PROMPT_CHARS)

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

        val enabledCount = repo.countEnabledByUser(userId, selfInitiated = false)

        if (enabledCount >= maxTasksPerUser) {
            return@suspendToolGuard "You already have $enabledCount scheduled tasks (limit $maxTasksPerUser). " +
                    "Cancel one with `cancelTask` before scheduling a new one."
        }

        val id =
            repo.create(
                newTask(
                    userId = userId,
                    chatId = chatId,
                    prompt = trimmedPrompt,
                    title = trimmedTitle,
                    recurrence = plan.recurrence,
                    timezone = tz,
                    nextFireAt = plan.firstFire,
                    selfInitiated = false
                )
            )

        "Scheduled task id=$id, fires=${formatFire(plan.firstFire, tz)} (${plan.recurrence.display})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.SCHEDULE_FOLLOW_UP)
    suspend fun scheduleFollowUp(
        @LLMDescription(TaskToolDescriptions.FOLLOW_UP_PROMPT)
        prompt: String,
        @LLMDescription(TaskToolDescriptions.FOLLOW_UP_AT)
        at: String,
        @LLMDescription(TaskToolDescriptions.FOLLOW_UP_TIMEZONE)
        timezone: String? = null,
        @LLMDescription(TaskToolDescriptions.FOLLOW_UP_TITLE)
        title: String? = null
    ): String = suspendToolGuard {
        val userId = context.requireUserId()
        val chatId = context.requireChatId()

        val trimmedPrompt = prompt.requireToolText("Follow-up prompt", MAX_PROMPT_CHARS)
        val trimmedTitle = title?.trim()?.takeIf { it.isNotEmpty() }

        require(trimmedTitle == null || trimmedTitle.length <= MAX_TITLE_CHARS) {
            "Follow-up title must be at most $MAX_TITLE_CHARS characters"
        }

        val tz =
            parseTimezone(timezone)
                ?: return@suspendToolGuard "Unknown timezone=`$timezone`. Use IANA names like `Europe/Kyiv` or omit."

        // a follow-up is by definition a single moment, so the recurring schedule forms are not offered
        // at all — the model only picks the datetime and the shared parser validates it.
        val plan =
            when (val parsed = parseSchedule("once ${at.trim()}", Instant.now(), tz)) {
                is ScheduleParse.Err -> return@suspendToolGuard parsed.message
                is ScheduleParse.Ok -> parsed
            }

        val pendingCount = repo.countEnabledByUser(userId, selfInitiated = true)

        if (pendingCount >= maxFollowUpsPerUser) {
            return@suspendToolGuard "You already owe this user $pendingCount follow-ups (limit $maxFollowUpsPerUser). " +
                    "Wait for one to fire instead of promising another."
        }

        val id =
            repo.create(
                newTask(
                    userId = userId,
                    chatId = chatId,
                    prompt = trimmedPrompt,
                    title = trimmedTitle,
                    recurrence = plan.recurrence,
                    timezone = tz,
                    nextFireAt = plan.firstFire,
                    selfInitiated = true
                )
            )

        "Follow-up id=$id set for ${formatFire(plan.firstFire, tz)}."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.LIST_TASKS)
    suspend fun listTasks(): String = suspendToolGuard {
        val userId = context.requireUserId()
        val scopedChatId = scopedChatId()

        val tasks = repo.listEnabledByUser(userId, scopedChatId)

        if (tasks.isEmpty())
            return@suspendToolGuard "No scheduled tasks."

        buildString {
            appendLine("Scheduled tasks (${tasks.size}):")
            tasks.forEach { append(formatTaskLine(it)).append('\n') }
        }.trimEnd()
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.EDIT_TASK)
    suspend fun editTask(
        @LLMDescription(TaskToolDescriptions.EDIT_ID)
        id: Long,
        @LLMDescription(TaskToolDescriptions.EDIT_PROMPT)
        prompt: String? = null,
        @LLMDescription(TaskToolDescriptions.EDIT_SCHEDULE)
        schedule: String? = null,
        @LLMDescription(TaskToolDescriptions.EDIT_TIMEZONE)
        timezone: String? = null,
        @LLMDescription(TaskToolDescriptions.EDIT_TITLE)
        title: String? = null
    ): String = suspendToolGuard {
        val userId = context.requireUserId()
        val scopedChatId = scopedChatId()

        if (listOf(prompt, schedule, timezone, title).all { it == null })
            return@suspendToolGuard "No changes provided for task id=$id."

        val existing =
            repo.findEnabledForUser(userId, id, scopedChatId)
                ?: return@suspendToolGuard taskNotFound(id, scopedChatId)

        val editedPrompt =
            prompt?.requireToolText("Task prompt", MAX_PROMPT_CHARS)
                ?: existing.prompt

        val editedTitle =
            if (title == null) {
                existing.title
            } else {
                title.trim().takeIf { it.isNotEmpty() }
            }

        require(title == null || editedTitle == null || editedTitle.length <= MAX_TITLE_CHARS) {
            "Task title must be at most $MAX_TITLE_CHARS characters"
        }

        val editedTimezone =
            if (timezone == null) {
                existing.timezone
            } else {
                runCatching { ZoneId.of(timezone.trim()) }.getOrNull()
                    ?: return@suspendToolGuard "Unknown timezone=`$timezone`. Use an IANA name like `Europe/Kyiv`."
            }

        val currentTime = Instant.now()

        val editedSchedule =
            if (schedule == null) {
                val nextFireAt =
                    if (timezone != null && existing.recurrence is Recurrence.Cron)
                        existing.recurrence.nextAfter(currentTime, editedTimezone)
                            ?: return@suspendToolGuard "The existing cron schedule has no upcoming fire time."
                    else
                        existing.nextFireAt

                ScheduleParse.Ok(existing.recurrence, nextFireAt)
            } else {
                when (val parsed = parseSchedule(schedule, currentTime, editedTimezone)) {
                    is ScheduleParse.Err -> return@suspendToolGuard parsed.message
                    is ScheduleParse.Ok -> parsed
                }
            }

        val edited =
            existing.copy(
                prompt = editedPrompt,
                title = editedTitle,
                recurrence = editedSchedule.recurrence,
                timezone = editedTimezone,
                nextFireAt = editedSchedule.firstFire
            )

        if (edited == existing)
            return@suspendToolGuard "Task id=$id already has the requested values."

        if (!repo.editEnabledForUser(userId, existing, edited, scopedChatId))
            return@suspendToolGuard "Task id=$id is no longer available."

        "Updated task id=$id (next=${formatFire(edited.nextFireAt, edited.timezone)}, " +
                "repeat=${edited.recurrence.display}, status=${if (edited.paused) "paused" else "active"})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.PAUSE_TASK)
    suspend fun pauseTask(
        @LLMDescription(TaskToolDescriptions.PAUSE_ID)
        id: Long
    ): String = suspendToolGuard {
        val userId = context.requireUserId()
        val scopedChatId = scopedChatId()

        val existing =
            repo.findEnabledForUser(userId, id, scopedChatId)
                ?: return@suspendToolGuard taskNotFound(id, scopedChatId)

        if (existing.paused)
            return@suspendToolGuard "Task id=$id is already paused."

        if (!repo.pauseForUser(userId, id, scopedChatId))
            return@suspendToolGuard "Task id=$id is no longer available."

        "Paused task id=$id (next=${formatFire(existing.nextFireAt, existing.timezone)})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.RESUME_TASK)
    suspend fun resumeTask(
        @LLMDescription(TaskToolDescriptions.RESUME_ID)
        id: Long
    ): String = suspendToolGuard {
        val userId = context.requireUserId()
        val scopedChatId = scopedChatId()

        val existing =
            repo.findEnabledForUser(userId, id, scopedChatId)
                ?: return@suspendToolGuard taskNotFound(id, scopedChatId)

        if (!existing.paused)
            return@suspendToolGuard "Task id=$id is already active."

        val nextFireAt =
            existing.nextFireAfterResume(Instant.now())
                ?: return@suspendToolGuard "Task id=$id is a one-time task whose scheduled time has passed. " +
                        "It cannot be resumed; schedule a new task instead."

        if (!repo.resumeForUser(userId, id, nextFireAt, scopedChatId))
            return@suspendToolGuard "Task id=$id is no longer available."

        "Resumed task id=$id (next=${formatFire(nextFireAt, existing.timezone)})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.CANCEL_TASK)
    suspend fun cancelTask(
        @LLMDescription(TaskToolDescriptions.CANCEL_ID)
        id: Long
    ): String = suspendToolGuard {
        val userId = context.requireUserId()
        val scopedChatId = scopedChatId()

        val existing =
            repo.findEnabledForUser(userId, id, scopedChatId)
                ?: return@suspendToolGuard taskNotFound(id, scopedChatId)

        if (!repo.deleteEnabledForUser(userId, id, scopedChatId))
            return@suspendToolGuard "Task id=$id is no longer available."

        "Cancelled task id=$id (${formatFire(existing.nextFireAt, existing.timezone)}, ${existing.recurrence.display})."
    }

    private fun newTask(
        userId: Long,
        chatId: Long,
        prompt: String,
        title: String?,
        recurrence: Recurrence,
        timezone: ZoneId,
        nextFireAt: Instant,
        selfInitiated: Boolean
    ) = NewScheduledTask(
        userId = userId,
        chatId = chatId,
        prompt = prompt,
        title = title,
        recurrence = recurrence,
        timezone = timezone,
        nextFireAt = nextFireAt,
        creatorMessageId = context.messageId.takeIf { it > 0L },
        creatorUsername = context.senderUsername,
        creatorDisplayName = context.senderDisplayName,
        chatIsPrivate = context.chatIsPrivate,
        language = context.language,
        selfInitiated = selfInitiated
    )

    private fun parseTimezone(raw: String?): ZoneId? {
        if (raw.isNullOrBlank()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(raw.trim()) }.getOrNull()
    }

    private fun scopedChatId(): Long? =
        context.requireChatId().takeUnless { context.chatIsPrivate }

    private fun taskNotFound(id: Long, scopedChatId: Long?): String =
        if (scopedChatId == null)
            "No scheduled task id=$id found for the current user."
        else
            "No scheduled task id=$id found for the current user in this chat."
}

private fun formatTaskLine(task: ScheduledTask): String =
    buildString {
        append("- id=").append(task.id)
        append(", fires=").append(formatFire(task.nextFireAt, task.timezone))
        append(", repeat=").append(task.recurrence.display)
        append(", status=").append(if (task.paused) "paused" else "active")
        if (task.selfInitiated) append(", source=your own follow-up")
        task.title?.let { append(", title=\"").append(it).append('"') }
        append(", prompt=\"").append(task.prompt).append('"')
    }
