package com.helltar.vusan.tools.message

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard

private const val MAX_MESSAGE_CHARS = 4000

// Telegram caps a rich message at 32768 UTF-8 characters (Bot API 10.1).
private const val MAX_RICH_MESSAGE_CHARS = 32768

@Suppress("unused")
class MessageTools(private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(MessageToolDescriptions.SEND_MESSAGE)
    suspend fun sendMessage(
        @LLMDescription(MessageToolDescriptions.TEXT)
        text: String
    ): String = suspendToolGuard {
        val trimmed = text.requireToolText("Message text", MAX_MESSAGE_CHARS)

        if (outbox.enqueueText(trimmed)) {
            "Delivered. The user has received this message. " +
                "Only call sendMessage again if you have a distinct additional message to send for this user request."
        } else {
            "Message limit reached: ${BotOutbox.MAX_TEXT_MESSAGES} separate messages are already queued for this reply. " +
                "Do not call sendMessage again; finish your turn now."
        }
    }

    @Tool
    @LLMDescription(MessageToolDescriptions.SEND_RICH_MESSAGE)
    suspend fun sendRichMessage(
        @LLMDescription(MessageToolDescriptions.RICH_MARKDOWN)
        markdown: String
    ): String = suspendToolGuard {
        val trimmed = markdown.requireToolText("Rich message", MAX_RICH_MESSAGE_CHARS)

        if (outbox.enqueueRichMessage(trimmed)) {
            "Delivered. The user has received this rich message. Do not repeat the same content with sendMessage."
        } else {
            "Message limit reached: ${BotOutbox.MAX_TEXT_MESSAGES} separate messages are already queued for this reply. " +
                "Do not send more; finish your turn now."
        }
    }

    @Tool
    @LLMDescription(MessageToolDescriptions.REPLY_IN_PRIVATE_MESSAGES)
    suspend fun replyInPrivateMessages(): String = suspendToolGuard {
        outbox.useDirectMessages()
        "Subsequent replies will be sent to the user's private chat."
    }
}
