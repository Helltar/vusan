package com.helltar.vusan.tools.quiz

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class QuizTools(private val outbox: BotOutbox) : ToolSet {

    @Tool(QuizToolDescriptions.CREATE_QUIZ)
    suspend fun createQuiz(
        @Arg(QuizToolDescriptions.QUESTION)
        question: String,
        @Arg(QuizToolDescriptions.OPTIONS)
        options: List<String>,
        @Arg(QuizToolDescriptions.CORRECT_OPTION_INDEX)
        correctOptionIndex: Int,
        @Arg(QuizToolDescriptions.EXPLANATION)
        explanation: String? = null,
        @Arg(QuizToolDescriptions.IS_ANONYMOUS)
        isAnonymous: Boolean = false,
    ): String = suspendToolGuard {
        val quiz =
            BotOutput.Quiz(
                question = question.trim(),
                options = options.map { it.trim() },
                correctOptionIndex = correctOptionIndex,
                explanation = explanation?.trim()?.takeIf { it.isNotEmpty() },
                isAnonymous = isAnonymous,
            )

        outbox.enqueue(quiz)

        """Quiz "${quiz.question}" ready with ${quiz.options.size} options and will be sent."""
    }
}
