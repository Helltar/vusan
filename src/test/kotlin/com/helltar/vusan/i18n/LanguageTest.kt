package com.helltar.vusan.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTest {

    @Test
    fun `maps primary subtag to language`() {
        assertEquals(Language.UKRAINIAN, Language.fromCode("uk"))
        assertEquals(Language.ENGLISH, Language.fromCode("en"))
    }

    @Test
    fun `ignores region subtag and casing`() {
        assertEquals(Language.ENGLISH, Language.fromCode("en-US"))
        assertEquals(Language.UKRAINIAN, Language.fromCode("UK-ua"))
        assertEquals(Language.UKRAINIAN, Language.fromCode("  uk  "))
    }

    @Test
    fun `falls back to default for blank or unknown codes`() {
        assertEquals(Language.DEFAULT, Language.fromCode(null))
        assertEquals(Language.DEFAULT, Language.fromCode(""))
        assertEquals(Language.DEFAULT, Language.fromCode("   "))
        assertEquals(Language.DEFAULT, Language.fromCode("de"))
        assertEquals(Language.DEFAULT, Language.fromCode("xx-YY"))
    }

    @Test
    fun `resolves messages per language`() {
        assertEquals(EnglishMessages, Messages.of(Language.ENGLISH))
        assertEquals(UkrainianMessages, Messages.of(Language.UKRAINIAN))
        assertEquals(UkrainianMessages, Messages.forCode("uk"))
        assertEquals(EnglishMessages, Messages.forCode(null))
    }
}
