package com.helltar.vusan.telegram

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AnsweredMessagesTest {

    private val now: Instant = Instant.parse("2026-08-28T12:00:00Z")
    private val chat = -100L

    @Test
    fun `the first claim on a message wins and the next one is refused`() {
        val answered = AnsweredMessages(5.minutes)

        assertTrue(answered.markAnswered(chat, messageId = 1L, now = now))
        assertFalse(answered.markAnswered(chat, messageId = 1L, now = now.plusSeconds(299)))
    }

    @Test
    fun `a message claimed again past the retention starts a turn of its own`() {
        val answered = AnsweredMessages(5.minutes)

        answered.markAnswered(chat, messageId = 1L, now = now)

        assertTrue(answered.markAnswered(chat, messageId = 1L, now = now.plusSeconds(301)))
    }

    @Test
    fun `another message in the same chat is claimed on its own`() {
        val answered = AnsweredMessages(5.minutes)

        answered.markAnswered(chat, messageId = 1L, now = now)

        assertTrue(answered.markAnswered(chat, messageId = 2L, now = now))
    }

    @Test
    fun `the same message id in another chat is a different message`() {
        val answered = AnsweredMessages(5.minutes)

        answered.markAnswered(chat, messageId = 1L, now = now)

        assertTrue(answered.markAnswered(chatId = -200L, messageId = 1L, now = now))
    }

    @Test
    fun `a later write drops what has aged out`() {
        // retention is what bounds the map, so eviction has to happen on write or it grows forever
        val answered = AnsweredMessages(5.minutes)

        answered.markAnswered(chat, messageId = 1L, now = now)
        answered.markAnswered(chat, messageId = 2L, now = now.plusSeconds(301))

        assertTrue(answered.markAnswered(chat, messageId = 1L, now = now.plusSeconds(301)))
    }
}
