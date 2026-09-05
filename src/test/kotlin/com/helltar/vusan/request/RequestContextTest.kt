package com.helltar.vusan.request

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestContextTest {

    private fun context(userId: Long) =
        RequestContext(chatId = -1002, userId = userId, messageId = 1L, chatIsPrivate = false)

    @Test
    fun `an ordinary sender identifies one person`() {
        assertTrue(context(4242).identifiesOnePerson)
    }

    @Test
    fun `the accounts telegram shares between senders do not`() {
        assertFalse(context(1_087_968_824).identifiesOnePerson, "GroupAnonymousBot")
        assertFalse(context(136_817_688).identifiesOnePerson, "Channel_Bot")
    }

    @Test
    fun `neither does a context without a sender`() {
        assertFalse(context(0).identifiesOnePerson)
    }
}
