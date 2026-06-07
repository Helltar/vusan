package com.helltar.vusan.tools.quiz

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class QuizTools(private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(QuizToolDescriptions.CREATE_QUIZ)
    suspend fun createQuiz(
        @LLMDescription(QuizToolDescriptions.QUESTION)
        question: String,
        @LLMDescription(QuizToolDescriptions.OPTIONS)
        options: List<String>,
        @LLMDescription(QuizToolDescriptions.CORRECT_OPTION_INDEX)
        correctOptionIndex: Int,
        @LLMDescription(QuizToolDescriptions.EXPLANATION)
        explanation: String? = null,
        @LLMDescription(QuizToolDescriptions.IS_ANONYMOUS)
        isAnonymous: Boolean = false
    ): String = suspendToolGuard {
        val quiz =
            BotOutput.Quiz(
                question = question.trim(),
                options = options.map { it.trim() },
                correctOptionIndex = correctOptionIndex,
                explanation = explanation?.trim()?.takeIf { it.isNotEmpty() },
                isAnonymous = isAnonymous
            )

        outbox.enqueue(quiz)

        """Quiz "${quiz.question}" ready with ${quiz.options.size} options and will be sent."""
    }
}
