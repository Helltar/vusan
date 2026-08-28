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
    fun `a remembered message is recognised inside the window`() {
        val answered = AnsweredMessages(5.minutes)

        answered.remember(chat, messageId = 1L, now = now)

        assertTrue(answered.contains(chat, messageId = 1L, now = now.plusSeconds(299)))
    }

    @Test
    fun `it forgets once the window has passed`() {
        val answered = AnsweredMessages(5.minutes)

        answered.remember(chat, messageId = 1L, now = now)

        assertFalse(answered.contains(chat, messageId = 1L, now = now.plusSeconds(301)))
    }

    @Test
    fun `a message it never saw is not remembered`() {
        val answered = AnsweredMessages(5.minutes)

        answered.remember(chat, messageId = 1L, now = now)

        assertFalse(answered.contains(chat, messageId = 2L, now = now))
    }

    @Test
    fun `the same message id in another chat is a different message`() {
        val answered = AnsweredMessages(5.minutes)

        answered.remember(chat, messageId = 1L, now = now)

        assertFalse(answered.contains(chatId = -200L, messageId = 1L, now = now))
    }

    @Test
    fun `a later write drops what has aged out`() {
        // the window is what bounds the map, so eviction has to happen on write or it grows forever
        val answered = AnsweredMessages(5.minutes)

        answered.remember(chat, messageId = 1L, now = now)
        answered.remember(chat, messageId = 2L, now = now.plusSeconds(301))

        assertFalse(answered.contains(chat, messageId = 1L, now = now))
        assertTrue(answered.contains(chat, messageId = 2L, now = now.plusSeconds(301)))
    }
}
