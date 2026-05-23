package com.helltar.vusan.tools.reaction

import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReactionToolsTest {

    @Test
    fun `setReaction defaults to current message id when no reply context`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L, replyToMessageId = null)
        val tools = ReactionTools(outbox)

        val result = tools.setReaction(emoji = "❤")

        assertEquals(BotOutput.Reaction(messageId = 100L, emoji = "❤"), outbox.pending.single())
        assertTrue("❤" in result)
        assertTrue("100" in result)
    }

    @Test
    fun `setReaction defaults to user's own message even when a reply target is in scope`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L, replyToMessageId = 55L)
        val tools = ReactionTools(outbox)

        tools.setReaction(emoji = "🔥")

        assertEquals(BotOutput.Reaction(messageId = 100L, emoji = "🔥"), outbox.pending.single())
    }

    @Test
    fun `setReaction targets the replied-to message when targetRepliedMessage is true`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L, replyToMessageId = 55L)
        val tools = ReactionTools(outbox)

        tools.setReaction(emoji = "🔥", targetRepliedMessage = true)

        assertEquals(BotOutput.Reaction(messageId = 55L, emoji = "🔥"), outbox.pending.single())
    }

    @Test
    fun `setReaction fails when targetRepliedMessage is set without a reply in scope`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L, replyToMessageId = null)
        val tools = ReactionTools(outbox)

        val result = tools.setReaction(emoji = "🔥", targetRepliedMessage = true)

        assertTrue("No replied-to message in scope" in result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `setReaction lets explicit messageId override targetRepliedMessage`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L, replyToMessageId = 55L)
        val tools = ReactionTools(outbox)

        tools.setReaction(emoji = "👍", targetRepliedMessage = true, messageId = 999L)

        assertEquals(BotOutput.Reaction(messageId = 999L, emoji = "👍"), outbox.pending.single())
    }

    @Test
    fun `setReaction honors explicit messageId over defaults`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L, replyToMessageId = 55L)
        val tools = ReactionTools(outbox)

        tools.setReaction(emoji = "👍", messageId = 999L)

        assertEquals(BotOutput.Reaction(messageId = 999L, emoji = "👍"), outbox.pending.single())
    }

    @Test
    fun `setReaction trims surrounding whitespace`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L)
        val tools = ReactionTools(outbox)

        tools.setReaction(emoji = "  🎉  ")

        assertEquals("🎉", (outbox.pending.single() as BotOutput.Reaction).emoji)
    }

    @Test
    fun `setReaction returns failure string for blank emoji`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L)
        val tools = ReactionTools(outbox)

        val result = tools.setReaction(emoji = "   ")

        assertTrue("Reaction emoji must be supplied" in result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `setReaction returns failure string when emoji argument is omitted entirely`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L)
        val tools = ReactionTools(outbox)

        val result = tools.setReaction()

        assertTrue("Reaction emoji must be supplied" in result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `setReaction returns failure string when no valid target available`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 0L, replyToMessageId = null)
        val tools = ReactionTools(outbox)

        val result = tools.setReaction(emoji = "❤")

        assertEquals("Tool failed: Reaction target message id must be positive", result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `setReaction rejects emoji outside Telegram free reaction set`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L)
        val tools = ReactionTools(outbox)

        val result = tools.setReaction(emoji = "👋")

        assertTrue("not in Telegram's free reaction set" in result)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `setReaction normalizes VS-16 to Telegram's canonical form`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L)
        val tools = ReactionTools(outbox)

        tools.setReaction(emoji = "❤️")

        assertEquals("❤", (outbox.pending.single() as BotOutput.Reaction).emoji)
    }

    @Test
    fun `enqueue skips private redirect for Reaction outputs`() = runBlocking {
        val outbox = BotOutbox(chatId = 42L, userId = 7L, messageId = 100L)
        outbox.useDirectMessages()
        val tools = ReactionTools(outbox)

        tools.setReaction(emoji = "👍")

        val item = outbox.pending.single()
        assertFalse(item.toPrivate)
    }
}
