package com.helltar.vusan.telegram.inbound

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.telegram.telegrambots.meta.api.objects.ExternalReplyInfo
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker

class MessageMetadataTest {

    @Test
    fun `each chat flavour gets its own prompt label`() {
        assertEquals("private", messageIn("""{"id": 5, "type": "private"}""").promptChatType())
        assertEquals("group", messageIn("""{"id": -100, "type": "group"}""").promptChatType())
        assertEquals("supergroup", messageIn("""{"id": -100, "type": "supergroup"}""").promptChatType())
        assertEquals("channel", messageIn("""{"id": -100, "type": "channel"}""").promptChatType())
        assertEquals("unknown", messageIn("""{"id": -100, "type": "sender"}""").promptChatType())
    }

    @Test
    fun `the forum flag refines a label rather than replacing it`() {
        assertEquals(
            "supergroup_forum",
            messageIn("""{"id": -100, "type": "supergroup", "is_forum": true}""").promptChatType()
        )

        assertEquals(
            "private_forum",
            messageIn("""{"id": 5, "type": "private", "is_forum": true}""").promptChatType()
        )
    }

    @Test
    fun `a business connection outranks the chat it arrives in`() {
        // the flags are not mutually exclusive, so the order of the ladder is the actual contract
        assertEquals(
            "business",
            message(""""business_connection_id": "conn-1"""", chat = """{"id": -100, "type": "supergroup"}""")
                .promptChatType()
        )
    }

    @Test
    fun `a direct-messages topic outranks the channel carrying it, but not a private chat`() {
        assertEquals(
            "channel_direct_messages",
            message(""""direct_messages_topic": {"topic_id": 4}""", chat = """{"id": -100, "type": "channel"}""")
                .promptChatType()
        )

        assertEquals(
            "private",
            message(""""direct_messages_topic": {"topic_id": 4}""", chat = """{"id": 5, "type": "private"}""")
                .promptChatType()
        )
    }

    @Test
    fun `a gif counts as an animation, not as the document telegram also sets on it`() {
        // telegram fills both fields for a gif; whichever branch runs first decides what the agent is told
        assertEquals("animation", gif().contentTypeName())
        assertEquals("0:03", gif().groupLogDescriptor())
    }

    @Test
    fun `content types are named for what the message actually carries`() {
        assertEquals("text", message(""""text": "hello"""").contentTypeName())
        assertEquals("poll", message(""""poll": {"id": "p", "question": "Tea or coffee?"}""").contentTypeName())
        assertEquals("dice", message(""""dice": {"emoji": "🎲", "value": 4}""").contentTypeName())
        assertEquals("venue", venue().contentTypeName())
        assertEquals("video", video(seconds = 12).contentTypeName())
        assertEquals("unknown", message().contentTypeName())
    }

    @Test
    fun `a duration is written as a clock, growing an hours field only when it needs one`() {
        assertEquals("0:07", video(seconds = 7).groupLogDescriptor())
        assertEquals("1:05", video(seconds = 65).groupLogDescriptor())
        assertEquals("59:59", video(seconds = 3_599).groupLogDescriptor())
        assertEquals("1:00:00", video(seconds = 3_600).groupLogDescriptor())
        assertEquals("1:01:05", video(seconds = 3_665).groupLogDescriptor())
    }

    @Test
    fun `a descriptor says what a person would say the message was`() {
        assertEquals(
            "😂 happy_cats",
            message(""""sticker": {"file_id": "s", "file_unique_id": "su", "width": 512, "height": 512,
                "type": "regular", "emoji": "😂", "set_name": "happy_cats"}""").groupLogDescriptor()
        )

        assertEquals("Tea or coffee?", message(""""poll": {"id": "p", "question": "Tea or coffee?"}""").groupLogDescriptor())
        assertEquals("The Old Library", venue().groupLogDescriptor())
        assertEquals("quarterly.pdf", document("quarterly.pdf").groupLogDescriptor())
    }

    @Test
    fun `a photo has no descriptor worth storing`() {
        // there is nothing a caption-less photo adds to a transcript that its kind does not already say
        assertNull(photo().groupLogDescriptor())
    }

    @Test
    fun `an audio track is named by its artist and title, and by its length when it has neither`() {
        assertEquals(
            "Marta Vane - Slow Harbour",
            message(""""audio": {"file_id": "a", "file_unique_id": "au", "duration": 200,
                "performer": "Marta Vane", "title": "Slow Harbour"}""").groupLogDescriptor()
        )

        assertEquals(
            "3:20",
            message(""""audio": {"file_id": "a", "file_unique_id": "au", "duration": 200}""").groupLogDescriptor()
        )
    }

    @Test
    fun `a descriptor is collapsed and capped to the column that stores it`() {
        // escaped so the fixture is valid JSON: telegram delivers these as real whitespace in the value
        assertEquals("spaced out name.pdf", document("""  spaced\n\tout   name.pdf  """).groupLogDescriptor())

        val descriptor = document("n".repeat(400) + ".pdf").groupLogDescriptor()

        assertTrue((descriptor?.length ?: 0) <= 200, "a descriptor longer than its varchar would be rejected on write")
    }

    @Test
    fun `photo metadata describes the biggest size and counts the rest`() {
        val lines = photo().mediaMetadataLines()

        assertTrue(lines.contains("file_id: big"), lines.toString())
        assertTrue(lines.contains("photo_sizes_count: 2"), lines.toString())
        assertTrue(lines.contains("biggest_photo_width: 1280"), lines.toString())
        assertTrue(lines.contains("file_size_bytes: 204800"), lines.toString())
    }

    @Test
    fun `the biggest photo is the one with the most pixels, not the widest or the last`() {
        val photos =
            message(""""photo": [
                {"file_id": "wide", "file_unique_id": "w", "width": 1000, "height": 100},
                {"file_id": "square", "file_unique_id": "s", "width": 400, "height": 400},
                {"file_id": "sizeless", "file_unique_id": "z"}
            ]""").photo

        assertEquals("square", photos.biggestOrNull()?.fileId)
    }

    @Test
    fun `file metadata carries what the model can act on`() {
        val lines = video(seconds = 12).mediaMetadataLines()

        assertTrue(lines.contains("file_id: v"), lines.toString())
        assertTrue(lines.contains("duration_seconds: 12"), lines.toString())
        assertTrue(lines.contains("mime_type: video/mp4"), lines.toString())
        assertTrue(lines.contains("width: 640"), lines.toString())
    }

    @Test
    fun `a sticker's format comes from the flags telegram sets, defaulting to static`() {
        assertEquals("animated", stickerOf(""""is_animated": true""").readableFormat())
        assertEquals("video", stickerOf(""""is_video": true""").readableFormat())
        assertEquals("static", stickerOf().readableFormat())
    }

    @Test
    fun `an incoming sticker is described with what is missing spelled out`() {
        val described = describeIncomingSticker(stickerOf(""""emoji": "😂""""))

        assertTrue(described.contains("Sticker emoji: 😂."), described)
        assertTrue(described.contains("Sticker pack: unknown."), described)
        assertTrue(described.contains("static regular sticker"), described)
    }

    @Test
    fun `an external reply is summarised by its media, then its story or poll`() {
        assertEquals("video", externalReply(""""video": {"file_id": "v", "file_unique_id": "vu"}""").summaryTypeNameOrNull())
        assertEquals("story", externalReply(""""story": {"id": 3, "chat": {"id": -100, "type": "channel"}}""").summaryTypeNameOrNull())
        assertEquals("poll", externalReply(""""poll": {"id": "p", "question": "Tea or coffee?"}""").summaryTypeNameOrNull())
        assertNull(externalReply().summaryTypeNameOrNull())
    }

    @Test
    fun `a display name joins the parts it has and ignores the ones it does not`() {
        assertEquals("Ada Bell", displayName("  Ada ", "Bell "))
        assertEquals("Ada", displayName("Ada", null))
        assertEquals("Bell", displayName(null, "Bell"))
        assertNull(displayName(null, null))
        assertNull(displayName("   ", ""))
    }

    @Test
    fun `a chat falls back to the person's name when it has no title`() {
        assertEquals(
            "Book Club",
            messageIn("""{"id": -100, "type": "supergroup", "title": "Book Club"}""").chat.titleOrDisplayName()
        )

        assertEquals(
            "Ada Bell",
            messageIn("""{"id": 5, "type": "private", "first_name": "Ada", "last_name": "Bell"}""").chat.titleOrDisplayName()
        )

        assertNull(messageIn("""{"id": 5, "type": "private"}""").chat.titleOrDisplayName())
    }

    private val mapper = ObjectMapper()

    private fun gif(): Message =
        message(
            """"animation": {"file_id": "a", "file_unique_id": "au", "width": 320, "height": 240, "duration": 3},
            "document": {"file_id": "d", "file_unique_id": "du", "file_name": "reaction.mp4"}"""
        )

    private fun video(seconds: Int): Message =
        message(
            """"video": {"file_id": "v", "file_unique_id": "vu", "width": 640, "height": 360,
            "duration": $seconds, "mime_type": "video/mp4"}"""
        )

    private fun photo(): Message =
        message(
            """"photo": [
                {"file_id": "small", "file_unique_id": "s", "width": 90, "height": 90},
                {"file_id": "big", "file_unique_id": "b", "width": 1280, "height": 720, "file_size": 204800}
            ]"""
        )

    private fun document(fileName: String): Message =
        message(""""document": {"file_id": "d", "file_unique_id": "du", "file_name": "$fileName"}""")

    private fun venue(): Message =
        message(
            """"venue": {"location": {"longitude": 1.0, "latitude": 2.0},
            "title": "The Old Library", "address": "12 Mill Lane"}"""
        )

    private fun stickerOf(fields: String = ""): Sticker =
        message(
            """"sticker": {"file_id": "s", "file_unique_id": "su", "width": 512, "height": 512,
            "type": "regular"${if (fields.isBlank()) "" else ", ${fields.trim()}"}}"""
        ).sticker

    private fun externalReply(fields: String = ""): ExternalReplyInfo =
        mapper.readValue(
            """{"origin": {"type": "hidden_user", "date": 1774000000, "sender_user_name": "Someone"}${
                if (fields.isBlank()) "" else ", ${fields.trim()}"
            }}""",
            ExternalReplyInfo::class.java
        )

    private fun messageIn(chat: String): Message = message(chat = chat)

    private fun message(fields: String = "", chat: String = """{"id": -100, "type": "supergroup"}"""): Message =
        mapper.readValue(
            """{"message_id": 1, "date": 1774000000, "chat": $chat${
                if (fields.isBlank()) "" else ", ${fields.trim()}"
            }}""",
            Message::class.java
        )
}
