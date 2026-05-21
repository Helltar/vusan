package com.helltar.vusan.tools.message

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.common.suspendToolGuard

private const val MAX_MESSAGE_CHARS = 4000

@Suppress("unused")
class MessageTools(private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(MessageToolDescriptions.SEND_MESSAGE)
    suspend fun sendMessage(
        @LLMDescription(MessageToolDescriptions.TEXT)
        text: String
    ): String = suspendToolGuard {
        val trimmed = text.trim()

        require(trimmed.isNotEmpty()) { "Message text must not be empty" }
        require(trimmed.length <= MAX_MESSAGE_CHARS) { "Message text must be at most $MAX_MESSAGE_CHARS characters" }

        outbox.enqueue(BotOutput.Text(trimmed))

        "Delivered. The user has received this message. " +
            "Only call sendMessage again if you have a distinct additional message to send for this user request."
    }

    @Tool
    @LLMDescription(MessageToolDescriptions.REPLY_IN_PRIVATE_MESSAGES)
    suspend fun replyInPrivateMessages(): String = suspendToolGuard {
        outbox.useDirectMessages()
        "Subsequent replies will be sent to the user's private chat."
    }
}
