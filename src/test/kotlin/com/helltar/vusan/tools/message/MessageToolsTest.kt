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
    fun `sendMessage preserves order across multiple calls`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        tools.sendMessage("first")
        tools.sendMessage("second")
        tools.sendMessage("third")

        assertEquals(
            listOf("first", "second", "third"),
            outbox.pending.map { assertIs<BotOutput.Text>(it.output).text }
        )
    }

    @Test
    fun `sendMessage caps separate messages per turn`() = runBlocking {
        val outbox = BotOutbox()
        val tools = MessageTools(outbox)

        repeat(BotOutbox.MAX_TEXT_MESSAGES) { i ->
            assertTrue(tools.sendMessage("message $i").startsWith("Delivered"))
        }

        val overflow = tools.sendMessage("one too many")

        assertTrue(overflow.startsWith("Message limit reached"))
        assertEquals(
            BotOutbox.MAX_TEXT_MESSAGES,
            outbox.pending.count { it.output is BotOutput.Text }
        )
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
