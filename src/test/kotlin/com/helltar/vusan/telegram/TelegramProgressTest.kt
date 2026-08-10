package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramProgressTest {

    // Telegram rejects draft id 0, which is exactly what a turn with no message behind it carries.
    @Test
    fun `a draft id is never zero and holds for one message`() {
        assertNotEquals(0, draftIdFor(0L))
        assertEquals(4321, draftIdFor(4321L))
        assertEquals(draftIdFor(4321L), draftIdFor(4321L))
        assertNotEquals(draftIdFor(4321L), draftIdFor(4322L))
    }

    @Test
    fun `a message id past Int range still yields a usable draft id`() {
        listOf(Int.MAX_VALUE.toLong(), Int.MAX_VALUE + 1L, Long.MAX_VALUE).forEach { messageId ->
            assertTrue(draftIdFor(messageId) > 0, "draft id for $messageId must be positive and non-zero")
        }
    }

    // the draft only folds into a message that starts with its own text, so the handoff must carry the
    // first thing delivery will actually send, and nothing at all when that is not plain text.
    @Test
    fun `the handoff carries the text that lands first`() {
        assertEquals("the answer", draftHandoffText(result(comment = "the answer")))
        assertEquals("queued", draftHandoffText(result(outputs = listOf(BotOutput.Text("queued")))))

        assertEquals(
            "queued",
            draftHandoffText(result(outputs = listOf(BotOutput.Text("queued")), comment = "trailing remark"))
        )
    }

    @Test
    fun `media, an empty turn and a blank remark hand over nothing`() {
        assertNull(draftHandoffText(result()))
        assertNull(draftHandoffText(result(comment = "   ")))
        assertNull(draftHandoffText(result(outputs = listOf(BotOutput.Reaction(messageId = 7L, emoji = "👍")))))

        val reaction = listOf(BotOutput.Reaction(messageId = 7L, emoji = "👍"))

        assertNull(draftHandoffText(result(outputs = reaction, comment = "caption for the media")))
    }

    private fun result(outputs: List<BotOutput> = emptyList(), comment: String? = null) =
        AgentResult(outputs = outputs.map { OutboxItem(it, toPrivate = false) }, comment = comment)
}
