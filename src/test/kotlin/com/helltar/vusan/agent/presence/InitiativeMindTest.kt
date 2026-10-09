package com.helltar.vusan.agent.presence

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitiativeMindTest {

    @Test
    fun `each action is read with what it needs`() {
        assertEquals(
            InitiativeDecision.Silent(why = "two people sorting out a trip"),
            parseInitiativeDecision("""{"action": "silent", "why": "two people sorting out a trip"}"""),
        )

        assertEquals(
            InitiativeDecision.React(target = 3, emoji = "😁"),
            parseInitiativeDecision("""{"action": "react", "target": 3, "emoji": "😁"}"""),
        )

        assertEquals(
            InitiativeDecision.Say(text = "take the ferry", replyTo = 2, why = "they asked the room"),
            parseInitiativeDecision("""{"action":"say","text":"take the ferry","reply_to":2,"why":"they asked the room"}"""),
        )

        assertEquals(
            InitiativeDecision.Sticker(id = 42, replyTo = 3, why = "the joke landed"),
            parseInitiativeDecision("""{"action": "sticker", "id": 42, "reply_to": 3, "why": "the joke landed"}"""),
        )
    }

    @Test
    fun `a decision wrapped in a code fence or in prose is still read`() {
        val answer = "Here you go:\n```json\n{\"action\": \"say\", \"text\": \"take the ferry\"}\n```"

        assertEquals(InitiativeDecision.Say(text = "take the ferry"), parseInitiativeDecision(answer))
    }

    @Test
    fun `an answer missing what its action needs is no decision`() {
        assertNull(parseInitiativeDecision("""{"action": "react", "emoji": "😁"}"""))
        assertNull(parseInitiativeDecision("""{"action": "react", "target": 3}"""))
        assertNull(parseInitiativeDecision("""{"action": "say", "text": "  "}"""))
        assertNull(parseInitiativeDecision("""{"action": "sticker", "reply_to": 2}"""))
        assertNull(parseInitiativeDecision("""{"action": "sing", "text": "la"}"""))
        assertNull(parseInitiativeDecision("""{"action": "say", "text": "unterminated"""))
        assertNull(parseInitiativeDecision("nothing to add"))
    }

    @Test
    fun `new lines follow the separator and only numbered ones can be pointed at`() {
        val rendered =
            renderGlance(
                listOf(
                    ChatGlanceLine(number = null, time = "11:58", author = "alice", content = "ferry or bridge", fresh = false),
                    ChatGlanceLine(number = null, time = "11:59", author = "you", content = "ferry", fresh = false),
                    ChatGlanceLine(number = 1, time = "12:14", author = "bob", content = "bridge is closed", fresh = true),
                ),
            )

        assertEquals(
            """
            11:58 alice: ferry or bridge
            11:59 you: ferry
            --- new since you were last here ---
            [1] 12:14 bob: bridge is closed
            """.trimIndent(),
            rendered,
        )
    }

    @Test
    fun `a spent day is stated and an open one is not`() {
        val open = initiativeState(input(maySpeak = true))
        val spent = initiativeState(input(maySpeak = false))

        assertFalse("only `silent` and `react`" in open)
        assertTrue("only `silent` and `react`" in spent)
    }

    @Test
    fun `the diary and the quiet people appear only when there are any`() {
        val bare = initiativeState(input())
        val full = initiativeState(input(diary = "2026-10-03: the ferry won", quietLately = listOf("carol — last wrote 5 days ago")))

        assertFalse("<diary>" in bare)
        assertFalse("<quiet_lately>" in bare)
        assertTrue("<diary>\n2026-10-03: the ferry won\n</diary>" in full)
        assertTrue("<quiet_lately>\ncarol — last wrote 5 days ago\n</quiet_lately>" in full)
    }

    @Test
    fun `the sticker shortlist appears as the catalog wrote it, only when the chat has one`() {
        val block = "<sticker_catalog>\n#3 penguin waving\n</sticker_catalog>"

        assertFalse("<sticker_catalog>" in initiativeState(input()))
        assertTrue(block in initiativeState(input(stickerCatalog = block)))
    }

    private fun input(
        maySpeak: Boolean = true,
        diary: String? = null,
        quietLately: List<String> = emptyList(),
        stickerCatalog: String? = null,
    ) =
        InitiativeInput(
            now = ZonedDateTime.parse("2026-10-04T12:15:00Z"),
            lines = listOf(ChatGlanceLine(1, "12:14", "bob", "bridge is closed", fresh = true)),
            saidToday = 1,
            maySpeak = maySpeak,
            diary = diary,
            quietLately = quietLately,
            stickerCatalog = stickerCatalog,
        )
}
