package com.helltar.vusan.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageTest {

    @Test
    fun `maps primary subtag to language`() {
        assertEquals(Language.UKRAINIAN, Language.fromCode("uk"))
        assertEquals(Language.ENGLISH, Language.fromCode("en"))
        assertEquals(Language.RUSSIAN, Language.fromCode("ru"))
        assertEquals(Language.SPANISH, Language.fromCode("es"))
    }

    @Test
    fun `ignores region subtag and casing`() {
        assertEquals(Language.ENGLISH, Language.fromCode("en-US"))
        assertEquals(Language.UKRAINIAN, Language.fromCode("UK-ua"))
        assertEquals(Language.UKRAINIAN, Language.fromCode("  uk  "))
        assertEquals(Language.SPANISH, Language.fromCode("es-MX"))
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
    fun `tells ukrainian and russian apart by the letters only one of them has`() {
        assertEquals(Language.UKRAINIAN, Language.ofText("привіт, шо там у вас", Language.ENGLISH))
        assertEquals(Language.RUSSIAN, Language.ofText("привет, ты где", Language.ENGLISH))
        assertEquals(Language.UKRAINIAN, Language.ofText("Є варіант", Language.RUSSIAN))
        assertEquals(Language.RUSSIAN, Language.ofText("Ещё раз", Language.UKRAINIAN))
    }

    @Test
    fun `falls back to everyday words when no letter settles it`() {
        assertEquals(Language.UKRAINIAN, Language.ofText("дякую, але треба ще", Language.ENGLISH))
        assertEquals(Language.RUSSIAN, Language.ofText("спасибо, но надо еще", Language.ENGLISH))
        assertEquals(Language.RUSSIAN, Language.ofText("кто и где", Language.UKRAINIAN))
        assertEquals(Language.UKRAINIAN, Language.ofText("а де вона", Language.RUSSIAN))
    }

    @Test
    fun `leaves what the text cannot settle to the client language`() {
        assertEquals(Language.SPANISH, Language.ofText("hola, qué tal", Language.SPANISH))
        assertEquals(Language.ENGLISH, Language.ofText("don't know", Language.ENGLISH))
        assertEquals(Language.ENGLISH, Language.ofText(null, Language.ENGLISH))
        assertEquals(Language.SPANISH, Language.ofText("   ", Language.SPANISH))
        assertEquals(Language.RUSSIAN, Language.ofText("ага", Language.RUSSIAN))
        assertEquals(Language.UKRAINIAN, Language.ofText("ага", Language.UKRAINIAN))
    }

    @Test
    fun `a cyrillic message from a latin-script client is answered in ukrainian`() {
        assertEquals(Language.UKRAINIAN, Language.ofText("ага", Language.ENGLISH))
        assertEquals(Language.UKRAINIAN, Language.ofText("Ну ок", Language.SPANISH))
    }

    @Test
    fun `resolves messages per language`() {
        assertEquals(EnglishMessages, Messages.of(Language.ENGLISH))
        assertEquals(UkrainianMessages, Messages.of(Language.UKRAINIAN))
        assertEquals(RussianMessages, Messages.of(Language.RUSSIAN))
        assertEquals(SpanishMessages, Messages.of(Language.SPANISH))
        assertEquals(UkrainianMessages, Messages.forCode("uk"))
        assertEquals(EnglishMessages, Messages.forCode(null))
    }
}
