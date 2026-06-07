package com.helltar.vusan.tools.poll

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class PollTools(private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(PollToolDescriptions.CREATE_POLL)
    suspend fun createPoll(
        @LLMDescription(PollToolDescriptions.QUESTION)
        question: String,
        @LLMDescription(PollToolDescriptions.OPTIONS)
        options: List<String>,
        @LLMDescription(PollToolDescriptions.IS_ANONYMOUS)
        isAnonymous: Boolean = true,
        @LLMDescription(PollToolDescriptions.ALLOWS_MULTIPLE_ANSWERS)
        allowsMultipleAnswers: Boolean = false
    ): String = suspendToolGuard {
        val poll =
            BotOutput.Poll(
                question = question.trim(),
                options = options.map { it.trim() },
                isAnonymous = isAnonymous,
                allowsMultipleAnswers = allowsMultipleAnswers
            )

        outbox.enqueue(poll)

        """Poll "${poll.question}" ready with ${poll.options.size} options and will be sent."""
    }
}
