package com.helltar.vusan.tools.message

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
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

// interim messages are for a result worth reading early; more than this is the turn narrating itself
private const val MAX_INTERIM_MESSAGES = 3

private const val QUEUE_FULL =
    "Message limit reached: ${BotOutbox.MAX_TEXT_MESSAGES} separate messages are already queued for this reply. " +
        "Do not send more; finish your turn now."

class MessageTools(
    private val outbox: BotOutbox,
    private val narrator: TurnNarrator? = null,
) : ToolSet {

    private var interimSent = 0

    @Tool(MessageToolDescriptions.SEND_MESSAGE)
    suspend fun sendMessage(
        @Arg(MessageToolDescriptions.TEXT, takesReference = true)
        text: String,
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

    @Tool(MessageToolDescriptions.SEND_RICH_MESSAGE)
    suspend fun sendRichMessage(
        @Arg(MessageToolDescriptions.RICH_MARKDOWN, takesReference = true)
        markdown: String,
    ): String = suspendToolGuard {
        val trimmed = markdown.requireToolText("Rich message", MAX_RICH_MESSAGE_CHARS)

        if (outbox.enqueueRichMessage(trimmed)) {
            "Delivered. The user has received this rich message. Do not repeat the same content with sendMessage."
        } else {
            "Message limit reached: ${BotOutbox.MAX_TEXT_MESSAGES} separate messages are already queued for this reply. " +
                "Do not send more; finish your turn now."
        }
    }

    @Tool(MessageToolDescriptions.ANNOUNCE_PLAN)
    suspend fun announcePlan(
        @Arg(MessageToolDescriptions.PLAN_TEXT)
        text: String,
    ): String = suspendToolGuard {
        val trimmed = text.requireToolText("Plan", MAX_ANNOUNCEMENT_CHARS)

        when {
            outbox.hasAnnounced ->
                "You have already announced this turn's plan and the user is reading it. " +
                    "Get on with the work and report the result at the end."

            // a reply the user asked to have in private must not surface in the chat the turn runs in, which
            // is where the live status is; the words travel to the private chat with the rest instead, and a
            // full queue refuses them rather than letting them through to the chat.
            outbox.redirectToPrivate ->
                if (outbox.enqueueText(trimmed, announcement = true)) {
                    "This reply goes to the user's private chat, so the plan was queued with it rather than shown here. " +
                        "Do not announce anything else; write the result into the same reply."
                } else {
                    QUEUE_FULL
                }

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

            else -> QUEUE_FULL
        }
    }

    @Tool(MessageToolDescriptions.SEND_MESSAGE_NOW)
    suspend fun sendMessageNow(
        @Arg(MessageToolDescriptions.NOW_TEXT)
        text: String,
    ): String = suspendToolGuard {
        val trimmed = text.requireToolText("Message text", MAX_MESSAGE_CHARS)

        when {
            interimSent >= MAX_INTERIM_MESSAGES ->
                "You have already sent $MAX_INTERIM_MESSAGES messages this way in this turn. " +
                    "Finish the work and put the rest into your final answer."

            // a reply the user asked to have in private must not surface in the chat the turn runs in; the
            // words travel to the private chat with the rest instead, and a full queue refuses them rather
            // than letting them through to the chat
            outbox.redirectToPrivate ->
                if (outbox.enqueueText(trimmed)) {
                    "This reply goes to the user's private chat, so the text was queued with the rest of it rather than sent here."
                } else {
                    QUEUE_FULL
                }

            // in the chat now, and recorded as an answer the turn gave rather than a promise it made
            narrator?.send(trimmed) == true -> {
                interimSent++
                outbox.recordDelivered(trimmed, announcement = false)
                "Sent. The user is reading it now; do not repeat it in your final answer."
            }

            // nobody is watching this turn go by, so the words travel with the answer instead
            outbox.enqueueText(trimmed) ->
                "This turn has no live chat to send into, so the text was queued with the rest of the reply."

            else -> QUEUE_FULL
        }
    }

    @Tool(MessageToolDescriptions.REPLY_IN_PRIVATE_MESSAGES)
    suspend fun replyInPrivateMessages(): String = suspendToolGuard {
        outbox.useDirectMessages()
        "Subsequent replies will be sent to the user's private chat."
    }
}
