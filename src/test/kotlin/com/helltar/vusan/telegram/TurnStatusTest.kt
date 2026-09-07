package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnStatusTest {

    @Test
    fun `the running line sits under the plan and carries its own ellipsis`() {
        assertEquals(
            "I will build the game\n\n<i>Running code…</i>",
            statusMessageText(plan = "I will build the game", label = "Running code", html = true)
        )
    }

    @Test
    fun `either half stands alone`() {
        assertEquals("<i>Searching the web…</i>", statusMessageText(plan = null, label = "Searching the web", html = true))
        assertEquals("I will build the game", statusMessageText(plan = "I will build the game", label = null, html = true))
    }

    // the bubble opens on the first named activity or the first thing the model says; before either
    // there is nothing to put in it.
    @Test
    fun `nothing to say yields no message`() {
        assertNull(statusMessageText(plan = null, label = null, html = true))
    }

    // a plan whose markup Telegram refused takes the whole status into plain text, and the italics of
    // the running line are markup too.
    @Test
    fun `plain text drops the italics`() {
        assertEquals(
            "I will build the game\n\nRunning code…",
            statusMessageText(plan = "I will build the game", label = "Running code", html = false)
        )
    }
}
