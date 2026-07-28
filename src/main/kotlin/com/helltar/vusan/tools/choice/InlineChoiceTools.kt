package com.helltar.vusan.tools.choice

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.requireUserId
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class InlineChoiceTools(
    private val context: RequestContext,
    private val outbox: BotOutbox,
    private val currentHistoryRevision: suspend (Long) -> Long
) : ToolSet {

    @Tool
    @LLMDescription(InlineChoiceToolDescriptions.ASK_WITH_BUTTONS)
    suspend fun askWithButtons(
        @LLMDescription(InlineChoiceToolDescriptions.QUESTION)
        question: String,
        @LLMDescription(InlineChoiceToolDescriptions.OPTIONS)
        options: List<String>
    ): String = suspendToolGuard {
        val ownerId = context.requireUserId()
        val choice =
            BotOutput.InlineChoice(
                question = question.trim(),
                options = options.map { it.trim() },
                ownerId = ownerId,
                historyRevision = currentHistoryRevision(ownerId)
            )

        if (outbox.enqueueInlineChoice(choice)) {
            "Question queued with ${choice.options.size} buttons. " +
                    "End your turn now and wait for the user's selection; do not send the question again."
        } else {
            "Message limit reached: ${BotOutbox.MAX_TEXT_MESSAGES} separate messages are already queued for this reply. " +
                    "Do not send more; finish your turn now."
        }
    }
}
