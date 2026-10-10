package com.helltar.vusan.tools.reaction

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.outbox.ALLOWED_REACTION_EMOJI
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.normalizeReactionEmoji
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.suspendToolGuard

class ReactionTools(private val context: RequestContext, private val outbox: BotOutbox) : ToolSet {

    @Tool(ReactionToolDescriptions.SET_REACTION)
    suspend fun setReaction(
        @Arg(ReactionToolDescriptions.EMOJI)
        emoji: String,
        @Arg(ReactionToolDescriptions.TARGET_REPLIED_MESSAGE)
        targetRepliedMessage: Boolean = false,
        @Arg(ReactionToolDescriptions.MESSAGE_ID)
        messageId: String? = null,
    ): String = suspendToolGuard {
        val trimmedEmoji = emoji.trim()

        require(trimmedEmoji.isNotEmpty()) { "Reaction emoji must be supplied — pass one of the chat's free reactions as `emoji`." }

        val normalized = normalizeReactionEmoji(trimmedEmoji)

        require(normalized in ALLOWED_REACTION_EMOJI) {
            "Emoji `$trimmedEmoji` is not in the chat's free reaction set and will be rejected. " +
                    "Pick one of these, or skip the reaction: ${ALLOWED_REACTION_EMOJI.joinToString(" ")}"
        }

        val targetId =
            when {
                !messageId.isNullOrBlank() -> messageId

                targetRepliedMessage -> requireNotNull(context.replyToMessageId) {
                    "No replied-to message in scope — drop `targetRepliedMessage` or " +
                            "react to the user's own message instead."
                }

                // a turn nothing sent — a scheduled task firing — has no message of its own to react to.
                else -> requireNotNull(context.messageId) {
                    "No message in scope to react to. Pass the `messageId` of the message you mean, " +
                            "or answer with text instead."
                }
            }

        outbox.enqueue(BotOutput.Reaction(messageId = targetId, emoji = normalized))

        "Reaction $normalized queued for message $targetId. " +
                "Do not also call sendMessage unless the user asked for an additional textual reply."
    }
}
