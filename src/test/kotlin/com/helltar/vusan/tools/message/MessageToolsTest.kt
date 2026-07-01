package com.helltar.vusan.tools.message

import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.BotOutbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MessageToolsTest {

    @Test
    fun `sendMessage enqueues trimmed text`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        val result = tools.sendMessage("   Hello, world!   ")

        assertTrue(result.startsWith("Delivered"))
        val text = assertIs<BotOutput.Text>(outbox.pending.single().output)
        assertEquals("Hello, world!", text.text)
    }

    @Test
    fun `sendMessage coalesces consecutive small messages into one bubble`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        tools.sendMessage("first")
        tools.sendMessage("second")
        tools.sendMessage("third")

        val text = assertIs<BotOutput.Text>(outbox.pending.single().output)
        assertEquals("first\n\nsecond\n\nthird", text.text)
    }

    @Test
    fun `sendMessage caps full-size bubbles per turn`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        // full-size messages cannot coalesce, so each one occupies its own bubble up to the cap.
        val fullSize = "a".repeat(BotOutbox.MAX_TEXT_MESSAGE_CHARS)
        repeat(BotOutbox.MAX_TEXT_MESSAGES) {
            assertTrue(tools.sendMessage(fullSize).startsWith("Delivered"))
        }

        val overflow = tools.sendMessage(fullSize)

        assertTrue(overflow.startsWith("Message limit reached"))
        assertEquals(
            BotOutbox.MAX_TEXT_MESSAGES,
            outbox.pending.count { it.output is BotOutput.Text }
        )
    }

    @Test
    fun `sendRichMessage enqueues a trimmed rich message`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        val result = tools.sendRichMessage("  # Title\n\n- one\n- two  ")

        assertTrue(result.startsWith("Delivered"))
        val rich = assertIs<BotOutput.RichMessage>(outbox.pending.single().output)
        assertEquals("# Title\n\n- one\n- two", rich.markdown)
    }

    @Test
    fun `sendRichMessage shares the bubble budget with plain text`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        val fullSize = "a".repeat(BotOutbox.MAX_TEXT_MESSAGE_CHARS)
        repeat(BotOutbox.MAX_TEXT_MESSAGES) {
            assertTrue(tools.sendMessage(fullSize).startsWith("Delivered"))
        }

        assertTrue(tools.sendRichMessage("# late").startsWith("Message limit reached"))
        assertTrue(outbox.pending.none { it.output is BotOutput.RichMessage })
    }

    @Test
    fun `sendRichMessage rejects blank markdown`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        val result = tools.sendRichMessage("   ")

        assertEquals("Tool failed: Rich message must not be empty", result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `sendMessage rejects blank text`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        val result = tools.sendMessage("   ")

        assertEquals("Tool failed: Message text must not be empty", result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `replyInPrivateMessages routes subsequent messages to private chat`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        tools.replyInPrivateMessages()
        tools.sendMessage("secret")

        val item = outbox.pending.single()
        assertIs<BotOutput.Text>(item.output)
        assertTrue(item.toPrivate)
        assertTrue(outbox.redirectToPrivate)
    }
}
