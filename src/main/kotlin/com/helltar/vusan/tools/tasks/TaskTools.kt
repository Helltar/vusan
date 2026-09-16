package com.helltar.vusan.tools.tasks

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tasks.*
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.time.Instant
import java.time.ZoneId

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
        @LLMDescription(TaskToolDescriptions.SCHEDULE_SPEC)
        schedule: String,
        @LLMDescription(TaskToolDescriptions.SCHEDULE_TIMEZONE)
        timezone: String? = null,
        @LLMDescription(TaskToolDescriptions.SCHEDULE_TITLE)
        title: String? = null,
    ): String = suspendToolGuard {
        val owner = context.user
        val chat = context.chatRef

        val trimmedPrompt = prompt.requireToolText("Task prompt", MAX_TASK_PROMPT_CHARS)

        val trimmedTitle = title?.trim()?.takeIf { it.isNotEmpty() }

        require(trimmedTitle == null || trimmedTitle.length <= MAX_TASK_TITLE_CHARS) {
            "Task title must be at most $MAX_TASK_TITLE_CHARS characters"
        }

        val tz =
            parseTimezone(timezone)
                ?: return@suspendToolGuard "Unknown timezone=`$timezone`. Use IANA names like `Europe/Kyiv` or omit."

        val plan =
            when (val parsed = parseSchedule(schedule, Instant.now(), tz)) {
                is ScheduleParse.Err -> return@suspendToolGuard parsed.message
                is ScheduleParse.Ok -> parsed
            }

        val enabledCount = repo.countForUser(owner, selfInitiated = false)

        if (enabledCount >= maxTasksPerUser) {
            return@suspendToolGuard "You already have $enabledCount scheduled tasks (limit $maxTasksPerUser). " +
                    "Cancel one with `cancelTask` before scheduling a new one."
        }

        val id =
            repo.create(
                context.newScheduledTask(
                    scope = ConversationScope(owner, chat),
                    prompt = trimmedPrompt,
                    title = trimmedTitle,
                    recurrence = plan.recurrence,
                    timezone = tz,
                    nextFireAt = plan.firstFire,
                    selfInitiated = false,
                ),
            )

        "Scheduled task id=$id, fires=${formatFire(plan.firstFire, tz)} (${plan.recurrence.display})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.LIST_TASKS)
    suspend fun listTasks(): String = suspendToolGuard {
        val owner = context.user
        val scopedChat = scopedChat()

        val tasks = repo.listForUser(owner, scopedChat)

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
        title: String? = null,
    ): String = suspendToolGuard {
        val owner = context.user
        val scopedChat = scopedChat()

        if (listOf(prompt, schedule, timezone, title).all { it == null })
            return@suspendToolGuard "No changes provided for task id=$id."

        val existing =
            repo.findForUser(owner, id, scopedChat)
                ?: return@suspendToolGuard taskNotFound(id, scopedChat)

        val editedPrompt =
            prompt?.requireToolText("Task prompt", MAX_TASK_PROMPT_CHARS)
                ?: existing.prompt

        val editedTitle =
            if (title == null) {
                existing.title
            } else {
                title.trim().takeIf { it.isNotEmpty() }
            }

        require(title == null || editedTitle == null || editedTitle.length <= MAX_TASK_TITLE_CHARS) {
            "Task title must be at most $MAX_TASK_TITLE_CHARS characters"
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
                nextFireAt = editedSchedule.firstFire,
            )

        if (edited == existing)
            return@suspendToolGuard "Task id=$id already has the requested values."

        if (!repo.editForUser(owner, existing, edited, scopedChat))
            return@suspendToolGuard "Task id=$id is no longer available."

        "Updated task id=$id (next=${formatFire(edited.nextFireAt, edited.timezone)}, " +
                "repeat=${edited.recurrence.display}, status=${if (edited.paused) "paused" else "active"})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.PAUSE_TASK)
    suspend fun pauseTask(
        @LLMDescription(TaskToolDescriptions.PAUSE_ID)
        id: Long,
    ): String = suspendToolGuard {
        val owner = context.user
        val scopedChat = scopedChat()

        val existing =
            repo.findForUser(owner, id, scopedChat)
                ?: return@suspendToolGuard taskNotFound(id, scopedChat)

        if (existing.paused)
            return@suspendToolGuard "Task id=$id is already paused."

        if (!repo.pauseForUser(owner, id, scopedChat))
            return@suspendToolGuard "Task id=$id is no longer available."

        "Paused task id=$id (next=${formatFire(existing.nextFireAt, existing.timezone)})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.RESUME_TASK)
    suspend fun resumeTask(
        @LLMDescription(TaskToolDescriptions.RESUME_ID)
        id: Long,
    ): String = suspendToolGuard {
        val owner = context.user
        val scopedChat = scopedChat()

        val existing =
            repo.findForUser(owner, id, scopedChat)
                ?: return@suspendToolGuard taskNotFound(id, scopedChat)

        if (!existing.paused)
            return@suspendToolGuard "Task id=$id is already active."

        val nextFireAt =
            existing.nextFireAfterResume(Instant.now())
                ?: return@suspendToolGuard "Task id=$id is a one-time task whose scheduled time has passed. " +
                        "It cannot be resumed; schedule a new task instead."

        if (!repo.resumeForUser(owner, id, nextFireAt, scopedChat))
            return@suspendToolGuard "Task id=$id is no longer available."

        "Resumed task id=$id (next=${formatFire(nextFireAt, existing.timezone)})."
    }

    @Tool
    @LLMDescription(TaskToolDescriptions.CANCEL_TASK)
    suspend fun cancelTask(
        @LLMDescription(TaskToolDescriptions.CANCEL_ID)
        id: Long,
    ): String = suspendToolGuard {
        val owner = context.user
        val scopedChat = scopedChat()

        val existing =
            repo.findForUser(owner, id, scopedChat)
                ?: return@suspendToolGuard taskNotFound(id, scopedChat)

        if (!repo.deleteForUser(owner, id, scopedChat))
            return@suspendToolGuard "Task id=$id is no longer available."

        "Cancelled task id=$id (${formatFire(existing.nextFireAt, existing.timezone)}, ${existing.recurrence.display})."
    }

    private fun scopedChat(): ChatRef? =
        context.chatRef.takeUnless { context.chat.isPrivate }

    private fun taskNotFound(id: Long, scopedChat: ChatRef?): String =
        if (scopedChat == null)
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
