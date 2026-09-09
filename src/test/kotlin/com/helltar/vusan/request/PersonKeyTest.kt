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
            sender = SenderContext(id = userId.toString(), isPerson = isPerson)
        ).personKeyOrNull

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
    fun `a sender the platform shares between people gets no key of their own`() {
        assertNull(key(1_087_968_824, -1002, private = false, isPerson = false))
    }

    // the workspace and site protocols take `u` plus digits, and a site's public address is built from
    // that number, so a second platform reusing the same shape would hand over somebody else's home.
    @Test
    fun `only telegram has a person key while the services take one id shape`() {
        val onDiscord =
            RequestContext(
                platform = Platform.DISCORD,
                chat = ChatContext(id = "4242", isPrivate = true),
                sender = SenderContext(id = "4242")
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
