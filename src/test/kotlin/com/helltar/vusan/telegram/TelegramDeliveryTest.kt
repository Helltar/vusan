package com.helltar.vusan.telegram

import com.helltar.vusan.outbox.BotOutput
import dev.inmo.tgbotapi.types.actions.RecordVideoNoteAction
import dev.inmo.tgbotapi.types.actions.RecordVoiceAction
import dev.inmo.tgbotapi.types.actions.TypingAction
import dev.inmo.tgbotapi.types.actions.UploadDocumentAction
import dev.inmo.tgbotapi.types.actions.UploadPhotoAction
import dev.inmo.tgbotapi.types.actions.UploadVideoAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramDeliveryTest {

    private val oneByte = ByteArray(1)

    @Test
    fun `maps each output kind to its matching chat action`() {
        assertEquals(TypingAction, botActionFor(BotOutput.Text("hi")))
        assertEquals(UploadPhotoAction, botActionFor(BotOutput.Photo(oneByte, "p.png")))
        assertEquals(
            UploadPhotoAction,
            botActionFor(BotOutput.PhotoGroup(listOf(BotOutput.Photo(oneByte, "a.png"), BotOutput.Photo(oneByte, "b.png"))))
        )
        assertEquals(UploadDocumentAction, botActionFor(BotOutput.Document(oneByte, "d.txt")))
        assertEquals(UploadVideoAction, botActionFor(BotOutput.Video(oneByte, "v.mp4")))
        assertEquals(UploadVideoAction, botActionFor(BotOutput.Animation(url = "https://example.com/a.gif")))
        assertEquals(RecordVideoNoteAction, botActionFor(BotOutput.VideoNote(oneByte)))
        assertEquals(RecordVoiceAction, botActionFor(BotOutput.Voice(oneByte)))
        assertEquals(
            UploadDocumentAction,
            botActionFor(BotOutput.Audio(oneByte, "s.mp3", title = "t", performer = "p"))
        )
    }

    @Test
    fun `reactions get no chat action`() {
        assertNull(botActionFor(BotOutput.Reaction(messageId = 1, emoji = "👍")))
    }
}
