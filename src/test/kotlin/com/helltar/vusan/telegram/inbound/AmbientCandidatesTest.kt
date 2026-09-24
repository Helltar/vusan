package com.helltar.vusan.telegram.inbound

import com.fasterxml.jackson.databind.ObjectMapper
import com.helltar.vusan.request.testChat
import org.telegram.telegrambots.meta.api.objects.message.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmbientCandidatesTest {

    @Test
    fun `a text typed into a group becomes a candidate with its author`() {
        val candidate = assertNotNull(message(""""text": "robin, what do you think"""").candidate())

        assertEquals(testChat(-100), candidate.chat)
        assertEquals("1", candidate.messageId)
        assertEquals("Bob Stone", candidate.author)
        assertEquals("robin, what do you think", candidate.text)
        assertNull(candidate.inReplyTo)
    }

    @Test
    fun `the author's waiting is passed through`() {
        assertTrue(assertNotNull(message(""""text": "how is it going"""").candidate(authorWaiting = true)).authorWaiting)
    }

    @Test
    fun `a caption counts as what the person said`() {
        val candidate = message(""""photo": [$PHOTO], "caption": "robin, look at this"""").candidate()

        assertEquals("robin, look at this", assertNotNull(candidate).text)
    }

    @Test
    fun `an album is judged on the part its caption is on`() {
        val anchor = message(""""photo": [$PHOTO], "media_group_id": "g1"""")
        val captioned = message(""""photo": [$PHOTO], "media_group_id": "g1", "caption": "robin, which one"""", id = 2)

        val candidate = assertNotNull(anchor.ambientCandidateOrNull(captioned, authorWaiting = false))

        assertEquals("2", candidate.messageId)
        assertEquals("robin, which one", candidate.text)
    }

    @Test
    fun `media without a caption has nothing to judge`() {
        assertNull(message(""""photo": [$PHOTO]""").candidate())
    }

    @Test
    fun `a reply to a person names that person`() {
        val candidate =
            message(
                """"text": "robin, is she right",
                "reply_to_message": {"message_id": 9, "date": 1774000000, "chat": {"id": -100, "type": "supergroup"},
                    "from": {"id": 8, "is_bot": false, "first_name": "Alice"}, "text": "it rains tomorrow"}""",
            ).candidate()

        assertEquals("Alice", assertNotNull(candidate).inReplyTo)
    }

    @Test
    fun `the implicit reply to a forum topic's opening message is no reply at all`() {
        val candidate =
            message(
                """"text": "robin, hello", "message_thread_id": 9, "is_topic_message": true,
                "reply_to_message": {"message_id": 9, "date": 1774000000, "chat": {"id": -100, "type": "supergroup"},
                    "from": {"id": 8, "is_bot": false, "first_name": "Alice"},
                    "forum_topic_created": {"name": "Plans", "icon_color": 7322096}}""",
            ).candidate()

        assertNull(assertNotNull(candidate).inReplyTo)
    }

    @Test
    fun `what a person did not type into the group is never a candidate`() {
        // a private chat is always answered, and needs no judging
        assertNull(message(""""text": "robin, hi"""", chat = """{"id": 7, "type": "private"}""").candidate())
        assertNull(message(""""text": "robin, hi", "edit_date": 1774000100""").candidate())
        assertNull(message(""""text": "robin, hi", "forward_origin": {"type": "hidden_user", "date": 1, "sender_user_name": "Carol"}""").candidate())
        assertNull(message(""""text": "robin, hi", "sender_chat": {"id": -100, "type": "supergroup", "title": "Group"}""").candidate())
        assertNull(message(""""text": "robin, hi", "via_bot": {"id": 5, "is_bot": true, "first_name": "Helper"}""").candidate())
        assertNull(message(""""text": "robin, hi"""", from = """{"id": 5, "is_bot": true, "first_name": "Helper"}""").candidate())
        assertNull(message(""""text": "   """").candidate())
    }

    @Test
    fun `a command is never a candidate`() {
        assertNull(
            message(""""text": "/tasks", "entities": [{"type": "bot_command", "offset": 0, "length": 6}]""").candidate(),
        )
    }

    private fun Message.candidate(authorWaiting: Boolean = false) =
        ambientCandidateOrNull(this, authorWaiting)

    private fun message(
        fields: String,
        id: Int = 1,
        chat: String = """{"id": -100, "type": "supergroup"}""",
        from: String = """{"id": 7, "is_bot": false, "first_name": "Bob", "last_name": "Stone", "username": "bob"}""",
    ): Message =
        mapper.readValue(
            """{"message_id": $id, "date": 1774000000, "chat": $chat, "from": $from, ${fields.trim()}}""",
            Message::class.java,
        )

    private companion object {
        val mapper = ObjectMapper()
        const val PHOTO = """{"file_id": "p1", "file_unique_id": "u1", "width": 10, "height": 10}"""
    }
}
