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
    fun `webPreviewUrl carries the search and the backwards cursor`() {
        val reference = TelegramChannelReference.parse("example_channel")

        assertEquals("https://t.me/s/example_channel", reference.webPreviewUrl())
        assertEquals("https://t.me/s/example_channel?before=517", reference.webPreviewUrl(before = 517))
        assertEquals(
            "https://t.me/s/example_channel?q=release%20notes&before=517",
            reference.webPreviewUrl(before = 517, query = "release notes")
        )
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
