package com.helltar.vusan.tools.poll

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.suspendToolGuard

class PollTools(private val outbox: BotOutbox) : ToolSet {

    @Tool(PollToolDescriptions.CREATE_POLL)
    suspend fun createPoll(
        @Arg(PollToolDescriptions.QUESTION)
        question: String,
        @Arg(PollToolDescriptions.OPTIONS)
        options: List<String>,
        @Arg(PollToolDescriptions.IS_ANONYMOUS)
        isAnonymous: Boolean = true,
        @Arg(PollToolDescriptions.ALLOWS_MULTIPLE_ANSWERS)
        allowsMultipleAnswers: Boolean = false,
    ): String = suspendToolGuard {
        val poll =
            BotOutput.Poll(
                question = question.trim(),
                options = options.map { it.trim() },
                isAnonymous = isAnonymous,
                allowsMultipleAnswers = allowsMultipleAnswers,
            )

        outbox.enqueue(poll)

        """Poll "${poll.question}" ready with ${poll.options.size} options and will be sent."""
    }
}
