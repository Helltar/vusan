package com.helltar.vusan.tools.choice

import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.request.testScope
import com.helltar.vusan.tools.toolFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class InlineChoiceToolsTest {

    @Test
    fun `askWithButtons stores a normalized owner-bound choice`() = runBlocking {
        val outbox = BotOutbox()
        var revisionScope: ConversationScope? = null
        val tools =
            InlineChoiceTools(requestContext(chatId = 7L, userId = 42L, messageId = "9"), outbox) { scope ->
                revisionScope = scope
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
                ownerId = "42",
                historyRevision = 7L,
                originMessageId = "9"
            ),
            outbox.pending.single().output
        )
        // a button is invalidated by the history of this chat, not of every chat this person writes in.
        assertEquals(testScope(userId = 42, chatId = 7), revisionScope)
    }

    // the question is what the answer will be tied back to, so a turn with no message of its own —
    // itself an answer to an earlier question — leaves the button without an origin instead of inventing one.
    @Test
    fun `a question asked by a turn without a message carries no origin`() = runBlocking {
        val outbox = BotOutbox()
        val tools = InlineChoiceTools(requestContext(chatId = 7L, userId = 42L, messageId = null), outbox) { _ -> 1L }

        tools.askWithButtons("Which format do you want?", listOf("PDF", "DOCX"))

        val choice = assertIs<BotOutput.InlineChoice>(outbox.pending.single().output)
        assertNull(choice.originMessageId)
    }

    @Test
    fun `askWithButtons rejects duplicate options`() = runBlocking {
        val outbox = BotOutbox()
        val tools = InlineChoiceTools(requestContext(chatId = 7L, userId = 42L, messageId = "9"), outbox) { _ -> 0L }

        val message = toolFailure { tools.askWithButtons("Continue?", listOf("Yes", "yes")) }

        assertEquals("Tool failed: Inline choice options must be distinct", message)
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
        val tools = InlineChoiceTools(requestContext(chatId = -7L, userId = 42L, messageId = "9"), outbox) { _ -> 0L }
        tools.askWithButtons("Continue in private?", listOf("Yes", "No"))

        val choice = outbox.pending.last()
        assertTrue(choice.toPrivate)
        assertIs<BotOutput.InlineChoice>(choice.output)
        assertFalse(outbox.enqueueText(full))
    }
}
