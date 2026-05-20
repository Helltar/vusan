package com.helltar.vusan.tools.tgchannel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramChannelReferenceTest {

    @Test
    fun `parse supports common public channel forms`() {
        assertEquals("example_channel", TelegramChannelReference.parse("@example_channel").username)
        assertEquals("example_channel", TelegramChannelReference.parse("t.me/example_channel").username)
        assertEquals("example_channel", TelegramChannelReference.parse("https://t.me/example_channel/123").username)
        assertEquals("example_channel", TelegramChannelReference.parse("https://t.me/s/example_channel").username)
    }

    @Test
    fun `parse rejects private and invite links`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramChannelReference.parse("https://t.me/c/123456/78")
        }
        assertFailsWith<IllegalArgumentException> {
            TelegramChannelReference.parse("https://t.me/+abcdef")
        }
    }
}
