package com.helltar.vusan.tools.poll

import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PollToolsTest {

    @Test
    fun `createPoll stores normalized poll in outbox`() = runBlocking {
        val outbox = BotOutbox()
        val tools = PollTools(outbox)

        val result =
            tools.createPoll(
                question = "  What's your favorite color?  ",
                options = listOf("  Red  ", "Green", "Blue"),
                isAnonymous = false,
                allowsMultipleAnswers = true
            )

        assertEquals("Poll \"What's your favorite color?\" ready with 3 options and will be sent.", result)
        assertEquals(
            BotOutput.Poll(
                question = "What's your favorite color?",
                options = listOf("Red", "Green", "Blue"),
                isAnonymous = false,
                allowsMultipleAnswers = true
            ),
            outbox.pending.single()
        )
    }

    @Test
    fun `createPoll defaults to anonymous single-choice`() = runBlocking {
        val outbox = BotOutbox()
        val tools = PollTools(outbox)

        tools.createPoll(
            question = "Tea or coffee?",
            options = listOf("Tea", "Coffee")
        )

        val poll = outbox.pending.single() as BotOutput.Poll

        assertEquals(true, poll.isAnonymous)
        assertEquals(false, poll.allowsMultipleAnswers)
    }

    @Test
    fun `createPoll returns failure string for too few options`() = runBlocking {
        val outbox = BotOutbox()
        val tools = PollTools(outbox)

        val result =
            tools.createPoll(
                question = "Is it worth it?",
                options = listOf("Yes")
            )

        assertEquals("Tool failed: Poll must have between 2 and 10 options", result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `createPoll rejects duplicate options ignoring case`() = runBlocking {
        val outbox = BotOutbox()
        val tools = PollTools(outbox)

        val result =
            tools.createPoll(
                question = "Choose a fruit",
                options = listOf("Apple", "apple", "Pear")
            )

        assertEquals("Tool failed: Poll options must be distinct", result)
        assertTrue(outbox.pending.isEmpty())
    }
}
