package com.helltar.vusan.tools.conversation

import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.agent.conversation.ConversationRepository
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class ConversationTools(private val history: ConversationRepository, private val context: RequestContext) : ToolSet {

    @Tool(ConversationToolDescriptions.CLEAR_CONVERSATION)
    suspend fun clearConversation(): String = suspendToolGuard {
        history.clear(context.scope)
        "Cleared this user's conversation history for this chat. Their history in other chats is untouched."
    }
}
