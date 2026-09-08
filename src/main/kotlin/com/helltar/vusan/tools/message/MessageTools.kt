package com.helltar.vusan.tools.message

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.TurnNarrator
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard

private const val MAX_MESSAGE_CHARS = 4000

// Telegram caps a rich message at 32768 UTF-8 characters (Bot API 10.1).
private const val MAX_RICH_MESSAGE_CHARS = 32768

// an announcement is one or two sentences about what comes next; anything longer is the answer itself,
// and the answer is not due yet.
private const val MAX_ANNOUNCEMENT_CHARS = 500

@Suppress("unused")
class MessageTools(
    private val outbox: BotOutbox,
    private val narrator: TurnNarrator? = null
) : ToolSet {

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
    @LLMDescription(MessageToolDescriptions.ANNOUNCE_PLAN)
    suspend fun announcePlan(
        @LLMDescription(MessageToolDescriptions.PLAN_TEXT)
        text: String
    ): String = suspendToolGuard {
        val trimmed = text.requireToolText("Plan", MAX_ANNOUNCEMENT_CHARS)

        when {
            outbox.hasDelivered ->
                "You have already announced this turn's plan and the user is reading it. " +
                    "Get on with the work and report the result at the end."

            // the turn has a live status to write into, and what it says is in the chat right now.
            narrator?.say(trimmed) == true -> {
                outbox.recordDelivered(trimmed)
                "Sent. The user can read this while the rest of the turn runs. " +
                    "Announce nothing further: do the work, and do not repeat these words in your final answer."
            }

            // nobody is watching this turn go by — a scheduled run has no one waiting on it — so the
            // words travel with the answer instead.
            outbox.enqueueText(trimmed, announcement = true) ->
                "This turn has no live chat to announce into, so the text was queued with the rest of the reply. " +
                    "Do not announce anything else; write the result into the same reply."

            else ->
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
