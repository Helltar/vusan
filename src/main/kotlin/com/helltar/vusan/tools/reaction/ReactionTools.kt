package com.helltar.vusan.tools.reaction

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.common.suspendToolGuard

// Free reaction emoji accepted by Telegram for bot-set reactions. Telegram's
// canonical forms are without VS-16 (U+FE0F); we normalize incoming emoji the
// same way before lookup so `❤️` and `❤` are treated as the same reaction.
private const val VARIATION_SELECTOR_16 = '️'

private val ALLOWED_REACTION_EMOJI: Set<String> =
    setOf(
        "👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱",
        "🤬", "😢", "🎉", "🤩", "🤮", "💩", "🙏", "👌", "🕊", "🤡",
        "🥱", "🥴", "😍", "🐳", "❤‍🔥", "🌚", "🌭", "💯", "🤣", "⚡",
        "🍌", "🏆", "💔", "🤨", "😐", "🍓", "🍾", "💋", "🖕", "😈",
        "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈", "😇", "😨",
        "🤝", "✍", "🤗", "🫡", "🎅", "🎄", "☃", "💅", "🤪", "🗿",
        "🆒", "💘", "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾",
        "🤷‍♂", "🤷", "🤷‍♀", "😡"
    )

private fun normalizeReactionEmoji(raw: String): String =
    raw.filterNot { it == VARIATION_SELECTOR_16 }

@Suppress("unused")
class ReactionTools(private val context: RequestContext, private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(ReactionToolDescriptions.SET_REACTION)
    suspend fun setReaction(
        @LLMDescription(ReactionToolDescriptions.EMOJI)
        emoji: String? = null,
        @LLMDescription(ReactionToolDescriptions.TARGET_REPLIED_MESSAGE)
        targetRepliedMessage: Boolean = false,
        @LLMDescription(ReactionToolDescriptions.MESSAGE_ID)
        messageId: Long? = null
    ): String = suspendToolGuard {
        val trimmedEmoji = emoji?.trim()

        require(!trimmedEmoji.isNullOrEmpty()) {
            "Reaction emoji must be supplied — pass one emoji from the allowed list as the `emoji` argument."
        }

        val normalized = normalizeReactionEmoji(trimmedEmoji)

        require(normalized in ALLOWED_REACTION_EMOJI) {
            "Emoji `$trimmedEmoji` is not in Telegram's free reaction set and will be rejected. " +
                    "Pick one from the allowed list in the tool description, or skip the reaction."
        }

        val targetId =
            when {
                messageId != null -> messageId

                targetRepliedMessage -> requireNotNull(context.replyToMessageId) {
                    "No replied-to message in scope — drop `targetRepliedMessage` or " +
                            "react to the user's own message instead."
                }

                else -> context.messageId
            }

        require(targetId > 0L) { "Reaction target message id must be positive" }

        outbox.enqueue(BotOutput.Reaction(messageId = targetId, emoji = normalized))

        "Reaction $normalized queued for message $targetId. " +
                "Do not also call sendMessage unless the user asked for an additional textual reply."
    }
}
