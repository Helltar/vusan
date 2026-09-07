package com.helltar.vusan.tools.message

import com.helltar.vusan.agent.TurnNarrator
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.toolFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // the whole point of an announcement: it reaches the chat now, and is recorded so the history and
    // the group transcript still carry what the bot said.
    @Test
    fun `announcePlan goes out through the live status and is recorded as delivered`() = runBlocking {
        val outbox = BotOutbox()
        val narrator = RecordingNarrator(reaches = true)

        val result = MessageTools(outbox, narrator).announcePlan("  I will build the game  ")

        assertTrue(result.startsWith("Sent"))
        assertEquals(listOf("I will build the game"), narrator.said)

        val item = outbox.pending.single()

        assertTrue(item.delivered)
        assertEquals("I will build the game", assertIs<BotOutput.Text>(item.output).text)
    }

    // a scheduled run has nobody watching it go by, so the words travel with the answer instead of
    // being dropped.
    @Test
    fun `announcePlan falls back to the queue when no one is watching`() = runBlocking {
        val outbox = BotOutbox()

        val result = MessageTools(outbox, narrator = null).announcePlan("I will build the game")

        assertTrue(result.contains("queued"))

        val item = outbox.pending.single()

        assertFalse(item.delivered)
        assertEquals("I will build the game", assertIs<BotOutput.Text>(item.output).text)
    }

    // the first announcement is what the user read; a second would rewrite it under them, and the
    // history would then claim two messages where the chat shows one.
    @Test
    fun `announcePlan refuses to announce twice`() = runBlocking {
        val outbox = BotOutbox()
        val narrator = RecordingNarrator(reaches = true)
        val tools = MessageTools(outbox, narrator)

        tools.announcePlan("I will build the game")
        val result = tools.announcePlan("actually, I will draw a picture")

        assertTrue(result.startsWith("You have already announced"))
        assertEquals(listOf("I will build the game"), narrator.said)
        assertEquals(1, outbox.pending.size)
    }

    @Test
    fun `announcePlan refuses empty text`() = runBlocking {
        toolFailure { MessageTools(BotOutbox(), RecordingNarrator(reaches = true)).announcePlan("   ") }
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

        val message = toolFailure { tools.sendRichMessage("   ") }

        assertEquals("Tool failed: Rich message must not be empty", message)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `sendMessage rejects blank text`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        val message = toolFailure { tools.sendMessage("   ") }

        assertEquals("Tool failed: Message text must not be empty", message)
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

    private class RecordingNarrator(private val reaches: Boolean) : TurnNarrator {

        val said = mutableListOf<String>()

        override suspend fun say(text: String): Boolean {
            if (reaches) said += text

            return reaches
        }
    }
}
