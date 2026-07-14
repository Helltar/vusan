package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.outbox.BotOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.telegram.telegrambots.meta.api.methods.ActionType

class TelegramDeliveryTest {

    private val oneByte = ByteArray(1)

    @Test
    fun `maps each output kind to its matching chat action`() {
        assertEquals(ActionType.TYPING, botActionFor(BotOutput.Text("hi")))
        assertEquals(ActionType.UPLOAD_PHOTO, botActionFor(BotOutput.Photo(oneByte, "p.png")))
        assertEquals(
            ActionType.UPLOAD_PHOTO,
            botActionFor(BotOutput.PhotoGroup(listOf(BotOutput.Photo(oneByte, "a.png"), BotOutput.Photo(oneByte, "b.png"))))
        )
        assertEquals(ActionType.UPLOAD_DOCUMENT, botActionFor(BotOutput.Document(oneByte, "d.txt")))
        assertEquals(ActionType.UPLOAD_VIDEO, botActionFor(BotOutput.Video(oneByte, "v.mp4")))
        assertEquals(ActionType.UPLOAD_VIDEO, botActionFor(BotOutput.Animation(url = "https://example.com/a.gif")))
        assertEquals(ActionType.RECORD_VIDEO_NOTE, botActionFor(BotOutput.VideoNote(oneByte)))
        assertEquals(ActionType.RECORD_VOICE, botActionFor(BotOutput.Voice(oneByte)))
        assertEquals(
            ActionType.UPLOAD_DOCUMENT,
            botActionFor(BotOutput.Audio(oneByte, "s.mp3", title = "t", performer = "p"))
        )
    }

    @Test
    fun `reactions get no chat action`() {
        assertNull(botActionFor(BotOutput.Reaction(messageId = 1, emoji = "👍")))
    }

    @Test
    fun `translates each tool activity to its chat action`() {
        assertEquals(ActionType.UPLOAD_PHOTO, chatActionFor(ToolActivity.PHOTO))
        assertEquals(ActionType.UPLOAD_VIDEO, chatActionFor(ToolActivity.VIDEO))
        assertEquals(ActionType.RECORD_VOICE, chatActionFor(ToolActivity.VOICE))
        assertEquals(ActionType.UPLOAD_DOCUMENT, chatActionFor(ToolActivity.DOCUMENT))
        assertEquals(ActionType.TYPING, chatActionFor(ToolActivity.TEXT))
    }
}
