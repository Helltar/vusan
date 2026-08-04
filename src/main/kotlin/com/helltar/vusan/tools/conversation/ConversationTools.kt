package com.helltar.vusan.tools.conversation

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.conversation.ConversationRepository
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.requireChatId
import com.helltar.vusan.request.requireUserId
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class ConversationTools(private val history: ConversationRepository, private val context: RequestContext) : ToolSet {

    @Tool
    @LLMDescription(ConversationToolDescriptions.CLEAR_CONVERSATION)
    suspend fun clearConversation(): String = suspendToolGuard {
        history.clear(context.requireUserId(), context.requireChatId())
        "Cleared this user's conversation history for this chat. Their history in other chats is untouched."
    }
}
