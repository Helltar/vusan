package com.helltar.vusan.telegram

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class TelegramBotRunnerTest {






    @Test
    fun `an edit starts a turn only when it is what addressed the message to the bot`() {
        assertTrue(edit())
    }

    @Test
    fun `an edit of a message the chat has left behind is not answered`() {
        // however fresh the edit, the reply would land under a message half an hour of conversation
        // above the current one — and telegram redelivering that message reads exactly the same way
        assertFalse(edit(sentAt = now.minusSeconds(301)))
        assertTrue(edit(sentAt = now.minusSeconds(299)))
    }

    @Test
    fun `a message that was never edited cannot start a turn`() {
        assertFalse(edit(editedAt = null))
    }

    @Test
    fun `editing a message into a command does not invoke it`() {
        // commands are invoked by sending them; `/clear` would wipe a history nobody asked it to
        assertFalse(edit(isCommand = true))
    }

    @Test
    fun `an edited album part does not answer for the album`() {
        assertFalse(edit(inAlbum = true))
    }

    private val now: Instant = Instant.parse("2026-08-28T12:00:00Z")

    private fun edit(
        sentAt: Instant = now.minusSeconds(30),
        editedAt: Instant? = now.minusSeconds(10),
        isCommand: Boolean = false,
        inAlbum: Boolean = false
    ): Boolean =
        startsTurnOnEdit(
            sentAt = sentAt,
            editedAt = editedAt,
            now = now,
            window = 5.minutes,
            isCommand = isCommand,
            inAlbum = inAlbum
        )

}
