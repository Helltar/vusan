package com.helltar.vusan.tools.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.common.suspendToolGuard

@Suppress("unused")
class MemoryTools(private val history: ChatHistoryRepository, private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(MemoryToolDescriptions.CLEAR_CHAT_HISTORY)
    suspend fun clearChatHistory(): String = suspendToolGuard {
        val userId = outbox.userId
        check(userId != 0L) { "User ID is unavailable for memory tools" }
        history.clear(userId)
        "Cleared chat history for the current user."
    }
}
