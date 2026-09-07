package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // the emoji is what a user reads first in a chat full of text, so an activity sharing another's
    // picture, or left without one, says less than the words already do.
    @Test
    fun `every activity is named behind an emoji of its own`() {
        val messages = Messages.of(Language.ENGLISH)

        val emojis =
            ToolActivity.entries.map { activity ->
                val line = activityStatusLabel(activity, messages)
                val emoji = line.substringBefore(' ')

                assertTrue(emoji.isNotBlank() && emoji.none { it.isLetterOrDigit() }, "$activity opens with no emoji: $line")
                assertEquals("$emoji ${messages.progressLabel(activity)}", line)

                emoji
            }

        assertEquals(emojis.size, emojis.toSet().size, "two activities share an emoji")
    }

    // the picture belongs to the activity, not to the language it is spelled in.
    @Test
    fun `the same activity carries the same emoji in every language`() {
        assertEquals("🎨 Drawing", activityStatusLabel(ToolActivity.DRAWING, Messages.of(Language.ENGLISH)))
        assertEquals("🎨 Малюю", activityStatusLabel(ToolActivity.DRAWING, Messages.of(Language.UKRAINIAN)))
    }
}
