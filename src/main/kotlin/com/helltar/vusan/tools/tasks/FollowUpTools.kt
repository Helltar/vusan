package com.helltar.vusan.tools.tasks

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tasks.ScheduleParse
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.tasks.formatFire
import com.helltar.vusan.tasks.parseSchedule
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.time.Instant

/**
 * The one scheduling tool the model reaches for on its own, so it stays visible while the rest of
 * the scheduler waits behind its group: nobody asks to be checked on, and a tool the model has to
 * load first for something nobody asked for is a tool it never calls.
 */
@Suppress("unused")
class FollowUpTools(
    private val repo: TasksRepository,
    private val context: RequestContext,
    private val maxFollowUpsPerUser: Int,
) : ToolSet {

    @Tool
    @LLMDescription(FollowUpToolDescriptions.SCHEDULE_FOLLOW_UP)
    suspend fun scheduleFollowUp(
        @LLMDescription(FollowUpToolDescriptions.PROMPT)
        prompt: String,
        @LLMDescription(FollowUpToolDescriptions.AT)
        at: String,
        @LLMDescription(FollowUpToolDescriptions.TIMEZONE)
        timezone: String? = null,
        @LLMDescription(FollowUpToolDescriptions.TITLE)
        title: String? = null,
    ): String = suspendToolGuard {
        val owner = context.user
        val chat = context.chatRef

        val trimmedPrompt = prompt.requireToolText("Follow-up prompt", MAX_TASK_PROMPT_CHARS)
        val trimmedTitle = title?.trim()?.takeIf { it.isNotEmpty() }

        require(trimmedTitle == null || trimmedTitle.length <= MAX_TASK_TITLE_CHARS) {
            "Follow-up title must be at most $MAX_TASK_TITLE_CHARS characters"
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

        val pendingCount = repo.countForUser(owner, selfInitiated = true)

        if (pendingCount >= maxFollowUpsPerUser) {
            return@suspendToolGuard "You already owe this user $pendingCount follow-ups (limit $maxFollowUpsPerUser). " +
                    "Wait for one to fire instead of promising another."
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
                    selfInitiated = true,
                ),
            )

        "Follow-up id=$id set for ${formatFire(plan.firstFire, tz)}."
    }
}
