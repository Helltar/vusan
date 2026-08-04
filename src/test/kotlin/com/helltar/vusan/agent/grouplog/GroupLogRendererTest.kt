package com.helltar.vusan.agent.grouplog

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupLogRendererTest {

    private val zone: ZoneId = ZoneId.of("Europe/Kyiv")
    private val noon: Instant = Instant.parse("2026-08-04T09:00:00Z")

    @Test
    fun `a plain message renders as time author and text`() {
        val rendered = render(listOf(entry(text = "hello")))

        assertEquals("12:00 olena: hello", rendered.text)
        assertEquals(1, rendered.includedCount)
    }

    @Test
    fun `an author with no username falls back to the display name and then to anon`() {
        val named = entry(text = "hi", username = null, name = "Olena Petrenko")
        val anonymous = entry(text = "hi", username = null, name = null)

        assertTrue(render(listOf(named)).text.contains("Olena Petrenko"))
        assertTrue(render(listOf(anonymous)).text.contains("anon"))
    }

    @Test
    fun `media renders as a marker instead of text`() {
        val sticker = entry(text = null, kind = "sticker", descriptor = "😂 HotCat")
        val voice = entry(text = null, kind = "voice", descriptor = "0:14")
        val photo = entry(text = null, kind = "photo", descriptor = null)

        assertEquals("12:00 olena [sticker 😂 HotCat]", render(listOf(sticker)).text)
        assertEquals("12:00 olena [voice 0:14]", render(listOf(voice)).text)
        assertEquals("12:00 olena [photo]", render(listOf(photo)).text)
    }

    @Test
    fun `a captioned photo keeps both the marker and the caption`() {
        val rendered = render(listOf(entry(text = "there", kind = "photo", descriptor = null)))

        assertEquals("12:00 olena [photo]: there", rendered.text)
    }

    @Test
    fun `a forward names where it came from`() {
        val rendered = render(listOf(entry(text = "long post", forwardFrom = "BBC News")))

        assertEquals("12:00 olena [forward from BBC News]: long post", rendered.text)
    }

    @Test
    fun `a bot row is labelled bot and does not stutter its descriptor`() {
        val text = entry(text = "done", kind = GroupLogEntry.BOT_KIND, username = null, name = null)
        val media = entry(text = null, kind = GroupLogEntry.BOT_KIND, descriptor = "photo", username = null, name = null)

        assertEquals("12:00 bot: done", render(listOf(text)).text)
        assertEquals("12:00 bot [photo]", render(listOf(media)).text)
    }

    @Test
    fun `a window spanning more than one day gains the date`() {
        val entries =
            listOf(
                entry(text = "yesterday", at = noon.minusSeconds(86_400)),
                entry(text = "today", at = noon)
            )

        val lines = render(entries).text.lines()

        assertEquals("08-03 12:00 olena: yesterday", lines[0])
        assertEquals("08-04 12:00 olena: today", lines[1])
    }

    @Test
    fun `text longer than the per-line cap is truncated`() {
        val rendered = render(listOf(entry(text = "x".repeat(500))), maxTextChars = 50)

        assertTrue(rendered.text.length < 100, "line was not capped: ${rendered.text.length} chars")
        assertTrue(rendered.text.endsWith("..."))
    }

    @Test
    fun `an overflowing budget keeps the newest lines and drops the oldest`() {
        val entries = (0 until 20).map { entry(text = "message $it", at = noon.plusSeconds(it.toLong())) }

        val rendered = render(entries, budgetChars = 100)

        assertTrue(rendered.includedCount in 1 until 20, "expected a partial render, got ${rendered.includedCount}")
        assertTrue(rendered.text.length <= 100)
        assertTrue(rendered.text.contains("message 19"), "the newest line must survive")
        assertFalse(rendered.text.contains("message 0:"), "the oldest line must be dropped")
    }

    @Test
    fun `one line is always rendered even when it alone busts the budget`() {
        val rendered = render(listOf(entry(text = "a".repeat(300))), budgetChars = 10)

        assertEquals(1, rendered.includedCount)
    }

    @Test
    fun `the asking user's own exchange with the bot is dropped as already in their history`() {
        val entries =
            listOf(
                entry(text = "has anyone seen the release?", username = "olena", senderId = 2L, messageId = 10L),
                entry(text = "@vusanbot hello", username = "helltar", senderId = 1L, messageId = 11L),
                botEntry(text = "Hi! How can I help?", answering = 11L, messageId = null)
            )

        val kept = entries.withoutExchangesWith(userId = 1L)

        assertEquals(listOf("has anyone seen the release?"), kept.map { it.text })
    }

    @Test
    fun `a bot reply to somebody else survives, because it is in nobody else's history`() {
        val entries =
            listOf(
                entry(text = "@vusanbot what about 2.3?", username = "olena", senderId = 2L, messageId = 10L),
                botEntry(text = "It is stable now", answering = 10L, messageId = null),
                entry(text = "@vusanbot will this break anything?", username = "serhii", senderId = 3L, messageId = 12L)
            )

        val kept = entries.withoutExchangesWith(userId = 3L)

        assertEquals(3, kept.size, "serhii has none of this in his own history")
    }

    @Test
    fun `an unanchored bot reply is left alone`() {
        val entries =
            listOf(
                entry(text = "@vusanbot hello", username = "helltar", senderId = 1L, messageId = 11L),
                botEntry(text = "exam reminder", answering = null, messageId = null)
            )

        val kept = entries.withoutExchangesWith(userId = 1L)

        assertEquals(2, kept.size, "with no anchor there is nothing proving a duplicate")
    }

    @Test
    fun `dropping exchanges leaves an unrelated log untouched`() {
        val entries = listOf(entry(text = "talking to herself", username = "olena", senderId = 2L, messageId = 10L))

        assertEquals(entries, entries.withoutExchangesWith(userId = 1L))
    }

    @Test
    fun `an empty log renders as nothing`() {
        val rendered = render(emptyList())

        assertEquals("", rendered.text)
        assertEquals(0, rendered.includedCount)
    }

    private fun render(
        entries: List<GroupLogEntry>,
        maxTextChars: Int = 300,
        budgetChars: Int = 10_000
    ) = renderGroupLog(entries, zone, maxTextChars, budgetChars)

    private fun entry(
        text: String?,
        at: Instant = noon,
        kind: String = "text",
        descriptor: String? = null,
        forwardFrom: String? = null,
        username: String? = "olena",
        name: String? = "Olena Petrenko",
        senderId: Long? = 2L,
        messageId: Long? = 1L
    ) =
        GroupLogEntry(
            chatId = -100L,
            messageId = messageId,
            kind = kind,
            sentAt = at,
            senderId = senderId,
            senderUsername = username,
            senderName = name,
            text = text,
            descriptor = descriptor,
            forwardFrom = forwardFrom
        )

    private fun botEntry(text: String, answering: Long?, messageId: Long?, at: Instant = noon) =
        GroupLogEntry(
            chatId = -100L,
            messageId = messageId,
            kind = GroupLogEntry.BOT_KIND,
            sentAt = at,
            text = text,
            replyToMessageId = answering
        )
}
