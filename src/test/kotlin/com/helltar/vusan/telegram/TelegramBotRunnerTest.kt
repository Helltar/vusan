package com.helltar.vusan.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import org.telegram.telegrambots.meta.api.objects.message.Message
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class TelegramBotRunnerTest {

    // a plain text a person sent on its own joins their run of lines; everything handled in a way of its own does not
    @Test
    fun `a plain text joins its sender's lines and anything handled on its own does not`() {
        assertTrue(message(""""text": "first line"""").joinsTextBatch())
        assertTrue(
            message(
                """
                "text": "in a topic",
                "reply_to_message": {"message_id": 1, "date": 1774000000, "chat": {"id": 10, "type": "supergroup"},
                                     "forum_topic_created": {"name": "Builds", "icon_color": 7322096}}
                """
            ).joinsTextBatch(),
        )

        assertFalse(message(""""text": "/tasks", "entities": [{"type": "bot_command", "offset": 0, "length": 6}]""").joinsTextBatch())
        assertFalse(
            message(
                """
                "text": "see above",
                "reply_to_message": {"message_id": 3, "date": 1774000000, "chat": {"id": 10, "type": "private"}, "text": "earlier"}
                """
            ).joinsTextBatch(),
        )
        assertFalse(
            message(""""text": "quoted", "forward_origin": {"type": "hidden_user", "sender_user_name": "Someone", "date": 1774000000}""").joinsTextBatch(),
        )
        assertFalse(message(""""text": "fixed", "edit_date": 1774000100""").joinsTextBatch())
        assertFalse(message(""""caption": "look", "photo": [{"file_id": "p", "file_unique_id": "u", "width": 1, "height": 1}]""").joinsTextBatch())
        assertFalse(message(""""text": "no sender"""", sender = "").joinsTextBatch())
        // anonymous admins all post as GroupAnonymousBot: two of them must not be read as one
        assertFalse(
            message(""""text": "as an admin"""", sender = """"from": {"id": 1087968824, "is_bot": true, "first_name": "Group"},""").joinsTextBatch(),
        )
    }

    private val mapper = ObjectMapper()

    // `date` must stay non-zero: the bot api models a zero date as an InaccessibleMessage subtype.
    private fun message(fields: String, sender: String = """"from": {"id": 5, "is_bot": false, "first_name": "Ada"},"""): Message =
        mapper.readValue(
            """{"message_id": 1, "date": 1774000000, "chat": {"id": 10, "type": "private"}, $sender ${fields.trim()}}""",
            Message::class.java,
        )

    @Test
    fun `an edit starts a turn only when it is what addressed the message to the bot`() {
        assertTrue(edit())
    }

    @Test
    fun `an edit of a message the chat has left behind is not answered`() {
        // however fresh the edit, the reply would land under a message half an hour of conversation
        // above the current one — and telegram redelivering that message reads exactly the same way
        assertFalse(edit(sentAt = now.minusSeconds(301)))
        assertTrue(edit(sentAt = now.minusSeconds(299)))
    }

    @Test
    fun `a message that was never edited cannot start a turn`() {
        assertFalse(edit(editedAt = null))
    }

    @Test
    fun `editing a message into a command does not invoke it`() {
        // commands are invoked by sending them; `/clear` would wipe a history nobody asked it to
        assertFalse(edit(isCommand = true))
    }

    @Test
    fun `an edited album part does not answer for the album`() {
        assertFalse(edit(inAlbum = true))
    }

    private val now: Instant = Instant.parse("2026-08-28T12:00:00Z")

    private fun edit(
        sentAt: Instant = now.minusSeconds(30),
        editedAt: Instant? = now.minusSeconds(10),
        isCommand: Boolean = false,
        inAlbum: Boolean = false,
    ): Boolean =
        startsTurnOnEdit(
            sentAt = sentAt,
            editedAt = editedAt,
            now = now,
            window = 5.minutes,
            isCommand = isCommand,
            inAlbum = inAlbum,
        )

}
