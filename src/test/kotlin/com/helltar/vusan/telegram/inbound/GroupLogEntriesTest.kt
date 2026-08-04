package com.helltar.vusan.telegram.inbound

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.telegram.telegrambots.meta.api.objects.message.Message

class GroupLogEntriesTest {

    @Test
    fun `a text message keeps its sender, text and telegram timestamp`() {
        val entry =
            assertNotNull(
                message(
                    """"text": "hello everyone",
                    "from": {"id": 7, "is_bot": false, "first_name": "Olena", "last_name": "Petrenko", "username": "olena"}"""
                ).toGroupLogEntry()
            )

        assertEquals(-100L, entry.chatId)
        assertEquals(1L, entry.messageId)
        assertEquals("text", entry.kind)
        assertEquals("hello everyone", entry.text)
        assertEquals(7L, entry.senderId)
        assertEquals("olena", entry.senderUsername)
        assertEquals("Olena Petrenko", entry.senderName)
        assertEquals(Instant.ofEpochSecond(1_774_000_000L), entry.sentAt)
    }

    @Test
    fun `a forward from a channel names the channel and is capped harder than plain text`() {
        val long = "p".repeat(3_000)
        val entry =
            assertNotNull(
                message(
                    """"text": "$long",
                    "forward_origin": {"type": "channel", "date": 1774000000,
                        "chat": {"id": -1001, "type": "channel", "title": "BBC News"}, "message_id": 5}"""
                ).toGroupLogEntry()
            )

        assertEquals("BBC News", entry.forwardFrom)
        assertTrue(entry.text!!.length <= 1_000, "a forwarded post must be capped tighter than typed text")
    }

    @Test
    fun `every forward origin variant resolves to a label`() {
        assertEquals(
            "BBC News",
            message(
                """"text": "x", "forward_origin": {"type": "channel", "date": 1, "message_id": 5,
                "chat": {"id": -1001, "type": "channel", "title": "BBC News"}}"""
            ).forwardOriginLabel()
        )

        assertEquals(
            "Some Group",
            message(
                """"text": "x", "forward_origin": {"type": "chat", "date": 1,
                "sender_chat": {"id": -1002, "type": "supergroup", "title": "Some Group"}}"""
            ).forwardOriginLabel()
        )

        assertEquals(
            "Serhii Koval",
            message(
                """"text": "x", "forward_origin": {"type": "user", "date": 1,
                "sender_user": {"id": 9, "is_bot": false, "first_name": "Serhii", "last_name": "Koval"}}"""
            ).forwardOriginLabel()
        )

        assertEquals(
            "Anonymous",
            message(
                """"text": "x", "forward_origin": {"type": "hidden_user", "date": 1, "sender_user_name": "Anonymous"}"""
            ).forwardOriginLabel()
        )
    }

    @Test
    fun `a message that is not a forward has no origin label`() {
        assertNull(message(""""text": "just typing"""").forwardOriginLabel())
    }

    @Test
    fun `media becomes a kind and a short descriptor, never a file id`() {
        val sticker =
            assertNotNull(
                message(
                    """"sticker": {"file_id": "abc", "file_unique_id": "u", "type": "regular",
                    "width": 512, "height": 512, "is_animated": false, "is_video": false,
                    "emoji": "😂", "set_name": "HotCat"}"""
                ).toGroupLogEntry()
            )

        assertEquals("sticker", sticker.kind)
        assertEquals("😂 HotCat", sticker.descriptor)
        assertNull(sticker.text)

        val voice =
            assertNotNull(
                message(""""voice": {"file_id": "abc", "file_unique_id": "u", "duration": 74}""").toGroupLogEntry()
            )

        assertEquals("voice", voice.kind)
        assertEquals("1:14", voice.descriptor)

        val document =
            assertNotNull(
                message(
                    """"document": {"file_id": "abc", "file_unique_id": "u", "file_name": "report.pdf"}"""
                ).toGroupLogEntry()
            )

        assertEquals("document", document.kind)
        assertEquals("report.pdf", document.descriptor)

        assertTrue(
            listOf(sticker, voice, document).none { it.descriptor?.contains("abc") == true },
            "file ids must never reach the transcript"
        )
    }

    @Test
    fun `a captioned photo keeps the caption as its text`() {
        val entry =
            assertNotNull(
                message(
                    """"caption": "there it is",
                    "photo": [{"file_id": "abc", "file_unique_id": "u", "width": 90, "height": 60}]"""
                ).toGroupLogEntry()
            )

        assertEquals("photo", entry.kind)
        assertEquals("there it is", entry.text)
    }

    @Test
    fun `a long duration renders with hours`() {
        val entry =
            assertNotNull(
                message(
                    """"audio": {"file_id": "abc", "file_unique_id": "u", "duration": 3723}"""
                ).toGroupLogEntry()
            )

        assertEquals("1:02:03", entry.descriptor)
    }

    @Test
    fun `a forum topic id is captured`() {
        val entry = assertNotNull(message(""""text": "x", "message_thread_id": 55""").toGroupLogEntry())

        assertEquals(55L, entry.threadId)
    }

    @Test
    fun `a reply records what it answers`() {
        val entry =
            assertNotNull(
                message(
                    """"text": "agreed", "reply_to_message": {"message_id": 40, "date": 1774000000,
                    "chat": {"id": -100, "type": "supergroup"}}"""
                ).toGroupLogEntry()
            )

        assertEquals(40L, entry.replyToMessageId)
    }

    @Test
    fun `a service message with neither text nor media is not recorded`() {
        assertNull(message(""""group_chat_created": true""").toGroupLogEntry())
    }

    private val mapper = ObjectMapper()

    // `date` must stay non-zero: the bot api models a zero date as an InaccessibleMessage subtype.
    private fun message(fields: String): Message =
        mapper.readValue(
            """{"message_id": 1, "date": 1774000000, "chat": {"id": -100, "type": "supergroup"}, ${fields.trim()}}""",
            Message::class.java
        )
}
