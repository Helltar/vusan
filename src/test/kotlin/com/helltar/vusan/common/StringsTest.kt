package com.helltar.vusan.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringsTest {

    // U+1F600 GRINNING FACE — a single astral-plane character encoded as a UTF-16 surrogate pair.
    private val emoji = "😀"

    private fun String.isValidUtf16(): Boolean =
        runCatching { encodeToByteArray() }.isSuccess

    @Test
    fun `limitTo never leaves a dangling surrogate when cut falls inside an emoji`() {
        // 7 emoji = 14 chars; every cap from 0..14 must stay valid UTF-16.
        val text = emoji.repeat(7)
        for (cap in 0..text.length + 2) {
            val result = text.limitTo(cap)
            assertTrue(result.isValidUtf16(), "limitTo($cap) produced malformed UTF-16: $result")
            assertFalse(result.lastOrNull()?.isHighSurrogate() == true, "limitTo($cap) ended on a high surrogate")
        }
    }

    @Test
    fun `collapseWhitespaceAndCap never leaves a dangling surrogate`() {
        val text = emoji.repeat(10)
        for (cap in 1..text.length + 2) {
            val result = text.collapseWhitespaceAndCap(cap)
            assertTrue(result == null || result.isValidUtf16(), "collapseWhitespaceAndCap($cap) produced malformed UTF-16")
        }
    }

    @Test
    fun `limitTo leaves short strings untouched`() {
        assertEquals("hello", "hello".limitTo(10))
        assertEquals(emoji, emoji.limitTo(2))
    }

    @Test
    fun `sanitizeFilename caps without splitting a surrogate`() {
        val name = emoji.repeat(200) + ".png"
        assertTrue(name.sanitizeFilename().isValidUtf16())
    }
}
