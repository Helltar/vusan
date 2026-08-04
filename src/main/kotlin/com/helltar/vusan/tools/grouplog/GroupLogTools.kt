package com.helltar.vusan.tools.grouplog

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.grouplog.GroupLogReader
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.requireChatId
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tools.suspendToolGuard
import kotlin.time.Duration.Companion.days

private val MAX_WINDOW = 90.days

@Suppress("unused")
class GroupLogTools(
    private val repository: GroupLogRepository,
    private val reader: GroupLogReader,
    private val context: RequestContext
) : ToolSet {

    @Tool
    @LLMDescription(GroupLogToolDescriptions.READ_GROUP_LOG)
    suspend fun readGroupLog(
        @LLMDescription(GroupLogToolDescriptions.READ_GROUP_LOG_WINDOW) window: String,
        @LLMDescription(GroupLogToolDescriptions.READ_GROUP_LOG_AUTHOR) author: String? = null
    ): String = suspendToolGuard {
        if (context.chatIsPrivate)
            return@suspendToolGuard "No group chat log in a private chat — this conversation is already your history."

        val parsed =
            Recurrence.parseInterval(window)
                ?: return@suspendToolGuard "Unknown window=`$window`. Use a duration like `30m`, `2h`, `24h`, or `7d`."

        if (parsed > MAX_WINDOW)
            return@suspendToolGuard "Window `$window` is too long. The chat log only reaches back `90d`."

        reader.read(
            chatId = context.requireChatId(),
            window = parsed,
            author = author?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    @Tool
    @LLMDescription(GroupLogToolDescriptions.CLEAR_GROUP_LOG)
    suspend fun clearGroupLog(): String = suspendToolGuard {
        if (context.chatIsPrivate)
            return@suspendToolGuard "No group chat log in a private chat — use `/clear` to wipe this conversation."

        val removed = repository.clear(context.requireChatId())

        "Deleted this group's recorded messages ($removed) and every cached daily recap."
    }
}
