package com.helltar.vusan.tools.quiz

import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.BotOutbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class QuizToolsTest {

    @Test
    fun `createQuiz stores normalized quiz in outbox`() = runBlocking {
        val outbox = BotOutbox()
        val tools = QuizTools(outbox)

        val result =
            tools.createQuiz(
                question = "  What has keys but cannot open doors?  ",
                options = listOf("  Piano  ", "Lock", "Lantern"),
                correctOptionIndex = 0,
                explanation = " Because a piano has keys, not door keys. "
            )

        assertEquals("Quiz \"What has keys but cannot open doors?\" ready with 3 options and will be sent.", result)
        assertEquals(
            BotOutput.Quiz(
                question = "What has keys but cannot open doors?",
                options = listOf("Piano", "Lock", "Lantern"),
                correctOptionIndex = 0,
                explanation = "Because a piano has keys, not door keys.",
                isAnonymous = false
            ),
            outbox.pending.single().output
        )
    }

    @Test
    fun `createQuiz returns failure string for invalid options`() = runBlocking {
        val outbox = BotOutbox()
        val tools = QuizTools(outbox)

        val result =
            tools.createQuiz(
                question = "Pick the correct answer",
                options = listOf("Yes"),
                correctOptionIndex = 0
            )

        assertEquals("Tool failed: Quiz must have between 2 and 10 options", result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `createQuiz rejects duplicate options ignoring case`() = runBlocking {
        val outbox = BotOutbox()
        val tools = QuizTools(outbox)

        val result =
            tools.createQuiz(
                question = "Choose a fruit",
                options = listOf("Apple", "apple", "Pear"),
                correctOptionIndex = 1
            )

        assertEquals("Tool failed: Quiz options must be distinct", result)
        assertTrue(outbox.pending.isEmpty())
    }
}
