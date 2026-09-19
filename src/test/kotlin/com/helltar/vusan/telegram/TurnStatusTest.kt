package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class TurnStatusTest {

    @Test
    fun `the running line sits under the plan and carries its own ellipsis`() {
        assertEquals(
            "I will build the game\n\nRunning code…",
            statusMessageText(plan = "I will build the game", label = "Running code"),
        )
    }

    @Test
    fun `either half stands alone`() {
        assertEquals("Searching the web…", statusMessageText(plan = null, label = "Searching the web"))
        assertEquals("I will build the game", statusMessageText(plan = "I will build the game", label = null))
    }

    // the bubble opens on the first named activity or the first thing the model says; before either
    // there is nothing to put in it.
    @Test
    fun `a fallback note sits under the running line`() {
        assertEquals(
            "I will build the game\n\n\uD83D\uDCBB Running code…\n\u21B3 on the fallback model: <code>gpt-5.4-mini</code>",
            statusMessageText(
                plan = "I will build the game",
                label = "\uD83D\uDCBB Running code",
                fallbackNote = Messages.of(Language.ENGLISH).fallbackModelNote("gpt-5.4-mini", primaryBackIn = null),
            ),
        )

        // a turn with nothing named yet still says where it is answering from
        assertEquals(
            "\u21B3 on the fallback model: <code>gpt-5.4-mini</code>",
            statusMessageText(plan = null, label = null, fallbackNote = Messages.of(Language.ENGLISH).fallbackModelNote("gpt-5.4-mini", primaryBackIn = null)),
        )
    }

    @Test
    fun `a fallback note says when the usual model is due back, if the provider said`() {
        assertEquals(
            "\u21B3 on the fallback model: <code>gpt-5.4-mini</code>\n\u21B3 the usual one is back in about <b>2h 15min</b>",
            Messages.of(Language.ENGLISH).fallbackModelNote("gpt-5.4-mini", primaryBackIn = 135.minutes),
        )
    }

    @Test
    fun `nothing to say yields no message`() {
        assertNull(statusMessageText(plan = null, label = null))
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
