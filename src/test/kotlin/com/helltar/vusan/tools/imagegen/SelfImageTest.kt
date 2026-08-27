package com.helltar.vusan.tools.imagegen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfImageTest {

    @Test
    fun `self-portrait prompt keeps the face and drops the rest of the reference`() {
        val prompt = selfPortraitPrompt("on a night tram, phone flash", appearance = null)

        assertContains(prompt, "reference image")
        assertContains(prompt, "same person")
        assertTrue(prompt.trimEnd().endsWith("on a night tram, phone flash"), "the scene has to close the prompt")
    }

    @Test
    fun `appearance notes sit between the identity rule and the scene`() {
        val prompt = selfPortraitPrompt("on a night tram", appearance = "tall, short bleached hair")

        assertTrue(prompt.indexOf("tall, short bleached hair") < prompt.indexOf("on a night tram"))
    }

    @Test
    fun `a prompt without appearance notes is left alone`() {
        assertEquals("a red panda", "a red panda".withAppearance(null))
    }

    @Test
    fun `appearance notes lead the prompt when there is no reference photo`() {
        val prompt = "on a night tram".withAppearance("tall, short bleached hair")

        assertTrue(prompt.startsWith("tall, short bleached hair"))
        assertContains(prompt, "on a night tram")
    }
}
