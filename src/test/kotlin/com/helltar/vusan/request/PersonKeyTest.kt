package com.helltar.vusan.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PersonKeyTest {

    private fun key(userId: Long, chatId: Long, private: Boolean, isPerson: Boolean = true) =
        RequestContext(
            platform = Platform.TELEGRAM,
            chat = ChatContext(id = chatId.toString(), isPrivate = private),
            sender = SenderContext(id = userId.toString(), isPerson = isPerson),
        ).personKeyOrNull

    @Test
    fun `a private chat is keyed by the person alone`() {
        assertEquals("telegram:4242", key(userId = 4242, chatId = 4242, private = true))
    }

    @Test
    fun `a group is keyed by the person alone`() {
        assertEquals("telegram:4242", key(4242, -1001234567890, private = false))
    }

    @Test
    fun `two people in one group get keys of their own`() {
        assertNotEquals(key(1, -1002, private = false), key(2, -1002, private = false))
    }

    @Test
    fun `a sender the platform shares between people gets no key of their own`() {
        assertNull(key(1_087_968_824, -1002, private = false, isPerson = false))
    }

    // a number from another messenger says nothing about a telegram account: a key without the platform
    // in it would hand one person's home to whoever elsewhere happens to have the same number.
    @Test
    fun `only telegram has a person key until another platform names itself in one`() {
        val onDiscord =
            RequestContext(
                platform = Platform.DISCORD,
                chat = ChatContext(id = "4242", isPrivate = true),
                sender = SenderContext(id = "4242"),
            )

        assertNull(onDiscord.personKeyOrNull)
    }

    @Test
    fun `the same person keeps one key across private chat and multiple groups`() {
        val inPrivate = key(7, 7, private = true)
        assertEquals(inPrivate, key(7, -1007, private = false))
        assertEquals(inPrivate, key(7, -2007, private = false))
    }
}
