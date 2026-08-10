package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressLabelTest {

    // the draft is the only place a user reads these, and an activity added without a translation
    // would surface as an empty bubble rather than as a compile error.
    @Test
    fun `every activity is spelled out in every language`() {
        Language.entries.forEach { language ->
            val messages = Messages.of(language)

            ToolActivity.entries.forEach { activity ->
                val label = messages.progressLabel(activity)

                assertTrue(label.isNotBlank(), "$language has no label for $activity")

                // the client animates its own ellipsis after the text, so a written one renders twice
                assertFalse(label.endsWith("…"), "$language label for $activity ends in an ellipsis: $label")
                assertFalse(label.endsWith("."), "$language label for $activity ends in a period: $label")
            }
        }
    }

    @Test
    fun `labels differ per language and per activity`() {
        assertEquals("Searching the web", Messages.of(Language.ENGLISH).progressLabel(ToolActivity.SEARCHING_WEB))
        assertEquals("Шукаю в інтернеті", Messages.of(Language.UKRAINIAN).progressLabel(ToolActivity.SEARCHING_WEB))

        Language.entries.forEach { language ->
            val labels = ToolActivity.entries.map { Messages.of(language).progressLabel(it) }
            assertEquals(labels.size, labels.toSet().size, "$language repeats a progress label")
        }
    }

    // the labels ride in a plain-text draft: markup would show up as literal characters.
    @Test
    fun `labels carry no markup`() {
        Language.entries.forEach { language ->
            ToolActivity.entries.forEach { activity ->
                assertFalse(Messages.of(language).progressLabel(activity).contains('<'))
            }
        }
    }
}
