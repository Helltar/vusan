package com.helltar.vusan.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringsTest {

    // grinning face U+1F600 — a single astral-plane character encoded as a UTF-16 surrogate pair.
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
    fun `collapseWhitespaceAndCap never exceeds the requested length`() {
        val text = "word ".repeat(50).trim()
        for (cap in 4..text.length + 2) {
            val result = text.collapseWhitespaceAndCap(cap)
            assertTrue((result?.length ?: 0) <= cap, "collapseWhitespaceAndCap($cap) returned ${result?.length} chars")
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

    @Test
    fun `sanitizeFilename strips path separators and header-breaking characters`() {
        assertEquals("evil.mp3", "../../evil.mp3".sanitizeFilename())
        assertEquals("evil.mp3", """..\..\evil.mp3""".sanitizeFilename())

        // quotes, CR and LF could break out of a quoted Content-Disposition filename.
        val cleaned = "a\"b\r\nc.mp3".sanitizeFilename()
        assertFalse('"' in cleaned, "quote survived sanitization: $cleaned")
        assertFalse('\r' in cleaned, "CR survived sanitization: $cleaned")
        assertFalse('\n' in cleaned, "LF survived sanitization: $cleaned")
    }

    // zero-width characters: U+200B zero-width space, U+200D zero-width joiner, U+FEFF BOM.
    // the Kotlin isBlank check treats them as non-blank, but Telegram rejects a message made only of
    // these with "text must be non-empty".
    private val zeroWidth = "\u200B\u200D\uFEFF"

    @Test
    fun `isEffectivelyBlank treats whitespace and zero-width characters as blank`() {
        assertTrue("".isEffectivelyBlank())
        assertTrue("   \n\t".isEffectivelyBlank())
        assertTrue(zeroWidth.isEffectivelyBlank())
        assertTrue(" $zeroWidth ".isEffectivelyBlank())
        assertFalse("${zeroWidth}content".isBlank())
    }

    @Test
    fun `isEffectivelyBlank keeps real content`() {
        assertFalse("hi".isEffectivelyBlank())
        assertFalse(emoji.isEffectivelyBlank())
        assertFalse(" ${zeroWidth}ok$zeroWidth ".isEffectivelyBlank())
    }
}
