package com.helltar.vusan.tools.choice

import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.RequestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class InlineChoiceToolsTest {

    @Test
    fun `askWithButtons stores a normalized owner-bound choice`() = runBlocking {
        val outbox = BotOutbox()
        var revisionOwnerId: Long? = null
        val tools =
            InlineChoiceTools(RequestContext(chatId = 7L, userId = 42L, messageId = 9L), outbox) { userId ->
                revisionOwnerId = userId
                7L
            }

        val result =
            tools.askWithButtons(
                question = "  Which format do you want?  ",
                options = listOf("  PDF  ", "DOCX")
            )

        assertEquals(
            "Question queued with 2 buttons. " +
                    "End your turn now and wait for the user's selection; do not send the question again.",
            result
        )
        assertEquals(
            BotOutput.InlineChoice(
                question = "Which format do you want?",
                options = listOf("PDF", "DOCX"),
                ownerId = 42L,
                historyRevision = 7L
            ),
            outbox.pending.single().output
        )
        assertEquals(42L, revisionOwnerId)
    }

    @Test
    fun `askWithButtons rejects duplicate options`() = runBlocking {
        val outbox = BotOutbox()
        val tools = InlineChoiceTools(RequestContext(chatId = 7L, userId = 42L, messageId = 9L), outbox) { 0L }

        val result = tools.askWithButtons("Continue?", listOf("Yes", "yes"))

        assertEquals("Tool failed: Inline choice options must be distinct", result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `inline choice shares the message cap and private routing`() = runBlocking {
        val outbox = BotOutbox()
        val full = "x".repeat(BotOutbox.MAX_TEXT_MESSAGE_CHARS)

        repeat(BotOutbox.MAX_TEXT_MESSAGES - 1) {
            assertTrue(outbox.enqueueText(full))
        }

        outbox.useDirectMessages()
        val tools = InlineChoiceTools(RequestContext(chatId = -7L, userId = 42L, messageId = 9L), outbox) { 0L }
        tools.askWithButtons("Continue in private?", listOf("Yes", "No"))

        val choice = outbox.pending.last()
        assertTrue(choice.toPrivate)
        assertIs<BotOutput.InlineChoice>(choice.output)
        assertFalse(outbox.enqueueText(full))
    }
}
