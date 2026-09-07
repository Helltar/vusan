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

    // an announcement is already in the chat: coalescing into it would rewrite a message the user has
    // read, and delivery would have no way to tell what still has to be sent.
    @Test
    fun `text never merges into what was already delivered`() {
        val outbox = BotOutbox()

        outbox.recordDelivered("I will build the game")

        assertTrue(outbox.hasDelivered)
        assertTrue(outbox.enqueueText("here it is"))

        val texts = outbox.pending.map { assertIs<BotOutput.Text>(it.output).text }

        assertEquals(listOf("I will build the game", "here it is"), texts)
        assertEquals(listOf(true, false), outbox.pending.map { it.delivered })
    }

    // the nudge that saves a silent turn reads this: a turn that only announced still owes an answer.
    @Test
    fun `an announcement alone leaves nothing queued`() {
        val outbox = BotOutbox()

        outbox.recordDelivered("I will build the game")
        assertFalse(outbox.hasQueuedOutput)

        outbox.enqueueText("here it is")
        assertTrue(outbox.hasQueuedOutput)
    }

    @Test
    fun `an announcement is never routed to a private chat`() {
        val outbox = BotOutbox()

        outbox.useDirectMessages()
        outbox.recordDelivered("I will build the game")

        assertFalse(outbox.pending.single().toPrivate)
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

    @Test
    fun `consecutive tracks become one album`() {
        val outbox = BotOutbox()

        outbox.enqueue(track("Nocturne"))
        outbox.enqueue(track("Prelude"))
        outbox.enqueue(track("Requiem"))

        val album = assertIs<BotOutput.AudioGroup>(outbox.pending.single().output)
        assertEquals(listOf("Nocturne", "Prelude", "Requiem"), album.audios.map { it.title })
    }

    @Test
    fun `a single track is sent on its own`() {
        val outbox = BotOutbox()

        outbox.enqueue(track("Nocturne"))

        assertIs<BotOutput.Audio>(outbox.pending.single().output)
    }

    @Test
    fun `anything queued between tracks starts a new album`() {
        val outbox = BotOutbox()

        outbox.enqueue(track("first"))
        outbox.enqueue(track("second"))
        assertTrue(outbox.enqueueText("and now the second half"))
        outbox.enqueue(track("third"))
        outbox.enqueue(track("fourth"))

        assertEquals(
            listOf("audioGroup(2)", "text", "audioGroup(2)"),
            outbox.pending.map {
                when (val output = it.output) {
                    is BotOutput.AudioGroup -> "audioGroup(${output.audios.size})"
                    is BotOutput.Text -> "text"
                    else -> "?"
                }
            }
        )
    }

    @Test
    fun `an eleventh track starts a second album instead of being dropped`() {
        val outbox = BotOutbox()

        repeat(11) { outbox.enqueue(track("track $it")) }

        val albums = outbox.pending.map { it.output }
        assertEquals(10, assertIs<BotOutput.AudioGroup>(albums.first()).audios.size)
        assertEquals("track 10", assertIs<BotOutput.Audio>(albums.last()).title)
    }

    @Test
    fun `a track redirected to direct messages does not join the album before it`() {
        val outbox = BotOutbox()

        outbox.enqueue(track("in the group"))
        outbox.useDirectMessages()
        outbox.enqueue(track("in the dm"))

        assertEquals(listOf(false, true), outbox.pending.map { it.toPrivate })
        assertEquals(2, outbox.pending.count { it.output is BotOutput.Audio })
    }

    private fun track(title: String) =
        BotOutput.Audio(
            bytes = ByteArray(4),
            filename = "$title.m4a",
            title = title,
            performer = "an orchestra"
        )
}
