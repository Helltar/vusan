package com.helltar.vusan.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BotOutboxTest {

    @Test
    fun `coalesces consecutive text into one bubble separated by a blank line`() {
        val outbox = BotOutbox()

        assertTrue(outbox.enqueueText("first"))
        assertTrue(outbox.enqueueText("second"))

        val text = assertIs<BotOutput.Text>(outbox.pending.single().output)
        assertEquals("first\n\nsecond", text.text)
    }

    @Test
    fun `starts a new bubble when a merge would exceed the char limit`() {
        val outbox = BotOutbox()
        val full = "a".repeat(BotOutbox.MAX_TEXT_MESSAGE_CHARS)

        assertTrue(outbox.enqueueText(full))
        assertTrue(outbox.enqueueText("b"))

        assertEquals(
            listOf(full, "b"),
            outbox.pending.map { assertIs<BotOutput.Text>(it.output).text }
        )
    }

    @Test
    fun `does not coalesce across a non-text output`() {
        val outbox = BotOutbox()

        outbox.enqueueText("before")
        outbox.enqueue(BotOutput.Photo(bytes = ByteArray(1), filename = "p.png"))
        outbox.enqueueText("after")

        assertEquals(3, outbox.pending.size)
        assertEquals("before", assertIs<BotOutput.Text>(outbox.pending.first().output).text)
        assertEquals("after", assertIs<BotOutput.Text>(outbox.pending.last().output).text)
    }

    @Test
    fun `does not coalesce when private routing changes between messages`() {
        val outbox = BotOutbox()

        outbox.enqueueText("public")
        outbox.useDirectMessages()
        outbox.enqueueText("private")

        assertEquals(2, outbox.pending.size)
        val (first, second) = outbox.pending
        assertFalse(first.toPrivate)
        assertTrue(second.toPrivate)
    }

    @Test
    fun `rich messages do not coalesce and share the bubble budget with text`() {
        val outbox = BotOutbox()

        assertTrue(outbox.enqueueRichMessage("# one"))
        assertTrue(outbox.enqueueText("plain"))
        assertTrue(outbox.enqueueRichMessage("# two"))

        assertEquals(3, outbox.pending.size)
        assertEquals("# one", assertIs<BotOutput.RichMessage>(outbox.pending.first().output).markdown)
    }

    @Test
    fun `text and rich messages together cannot exceed the standalone bubble cap`() {
        val outbox = BotOutbox()
        val full = "a".repeat(BotOutbox.MAX_TEXT_MESSAGE_CHARS)

        repeat(BotOutbox.MAX_TEXT_MESSAGES - 1) {
            assertTrue(outbox.enqueueText(full))
        }
        assertTrue(outbox.enqueueRichMessage("# last"))

        assertFalse(outbox.enqueueText(full))
        assertFalse(outbox.enqueueRichMessage("# overflow"))
        assertEquals(BotOutbox.MAX_TEXT_MESSAGES, outbox.pending.size)
    }

    @Test
    fun `caps full-size bubbles and rejects the overflow`() {
        val outbox = BotOutbox()
        val full = "a".repeat(BotOutbox.MAX_TEXT_MESSAGE_CHARS)

        repeat(BotOutbox.MAX_TEXT_MESSAGES) {
            assertTrue(outbox.enqueueText(full))
        }

        assertFalse(outbox.enqueueText(full))
        assertEquals(BotOutbox.MAX_TEXT_MESSAGES, outbox.pending.count { it.output is BotOutput.Text })
    }
}
