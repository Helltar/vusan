package com.helltar.vusan.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PersonKeyTest {

    private fun key(userId: Long, chatId: Long, private: Boolean) =
        RequestContext(chatId = chatId, userId = userId, messageId = 1L, chatIsPrivate = private).personKeyOrNull

    @Test
    fun `a private chat is keyed by the person alone`() {
        assertEquals("u4242", key(userId = 4242, chatId = 4242, private = true))
    }

    @Test
    fun `a group is keyed by the person alone`() {
        assertEquals("u4242", key(4242, -1001234567890, private = false))
    }

    @Test
    fun `two people in one group get keys of their own`() {
        assertNotEquals(key(1, -1002, private = false), key(2, -1002, private = false))
    }

    @Test
    fun `senders telegram delivers under a shared bot account get no key of their own`() {
        assertNull(key(1_087_968_824, -1002, private = false))
        assertNull(key(136_817_688, -1002, private = false))
    }

    @Test
    fun `the same person keeps one key across private chat and multiple groups`() {
        val inPrivate = key(7, 7, private = true)
        assertEquals(inPrivate, key(7, -1007, private = false))
        assertEquals(inPrivate, key(7, -2007, private = false))
    }
}
