package com.helltar.vusan.tools.history

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class HistoryTools(private val history: ChatHistoryRepository, private val context: RequestContext) : ToolSet {

    @Tool
    @LLMDescription(HistoryToolDescriptions.CLEAR_CHAT_HISTORY)
    suspend fun clearChatHistory(): String = suspendToolGuard {
        val userId = context.userId
        check(userId != 0L) { "User ID is unavailable for history tools" }
        history.clear(userId)
        "Cleared chat history for the current user."
    }
}
