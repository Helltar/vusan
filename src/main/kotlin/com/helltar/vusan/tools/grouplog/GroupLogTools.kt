package com.helltar.vusan.tools.grouplog

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.grouplog.GroupLogReader
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tools.suspendToolGuard
import kotlin.time.Duration.Companion.days

@Suppress("unused")
class GroupLogTools(
    private val repository: GroupLogRepository,
    private val reader: GroupLogReader,
    private val context: RequestContext,
) : ToolSet {

    // a window past retention would read as an empty chat rather than as a limit, so it is refused
    // with the number instead.
    private val maxWindow = repository.retentionDays.days

    @Tool
    @LLMDescription(GroupLogToolDescriptions.READ_GROUP_LOG)
    suspend fun readGroupLog(
        @LLMDescription(GroupLogToolDescriptions.READ_GROUP_LOG_WINDOW) window: String,
        @LLMDescription(GroupLogToolDescriptions.READ_GROUP_LOG_AUTHOR) author: String? = null,
    ): String = suspendToolGuard {
        if (context.chat.isPrivate)
            return@suspendToolGuard "No group chat log in a private chat — this conversation is already your history."

        val parsed =
            Recurrence.parseInterval(window)
                ?: return@suspendToolGuard "Unknown window=`$window`. Use a duration like `30m`, `2h`, `24h`, or `7d`."

        if (parsed > maxWindow) {
            return@suspendToolGuard "Window `$window` is too long. " +
                    "The chat log is kept for ${repository.retentionDays} days, so ask for at most `${repository.retentionDays}d`."
        }

        reader.read(
            chat = context.chatRef,
            window = parsed,
            author = author?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    @Tool
    @LLMDescription(GroupLogToolDescriptions.CLEAR_GROUP_LOG)
    suspend fun clearGroupLog(): String = suspendToolGuard {
        if (context.chat.isPrivate)
            return@suspendToolGuard "No group chat log in a private chat — use `/clear` to wipe this conversation."

        val removed = repository.clear(context.chatRef)

        "Deleted this group's recorded messages ($removed) and every cached daily recap."
    }
}
