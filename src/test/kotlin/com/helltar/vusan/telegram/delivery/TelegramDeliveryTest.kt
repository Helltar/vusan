package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import java.io.Serializable
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient

class TelegramDeliveryTest {

    private val oneByte = ByteArray(1)

    @Test
    fun `maps each output kind to its matching chat action`() {
        assertEquals(ActionType.TYPING, botActionFor(BotOutput.Text("hi")))
        assertEquals(
            ActionType.TYPING,
            botActionFor(
                BotOutput.InlineChoice(
                    question = "Choose",
                    options = listOf("A", "B"),
                    ownerId = 1L,
                    historyRevision = 0L
                )
            )
        )
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
    fun `a sticker telegram will not accept is reported to the catalog`() = runBlocking {
        val rejected = mutableListOf<Long>()
        val client = StickerRejectingClient("Bad Request: wrong remote file identifier specified")

        deliverSticker(TelegramDelivery(client.proxy) { rejected += it })

        assertEquals(listOf(42L), rejected)
    }

    @Test
    fun `a sticker refused for reasons of its own is not blamed on the catalog`() = runBlocking {
        val rejected = mutableListOf<Long>()
        // a group where admins restricted stickers rejects every one of them; the catalog is shared
        // by every chat, so this must not be read as the sticker being broken.
        val client = StickerRejectingClient("Bad Request: not enough rights to send stickers to the chat")

        deliverSticker(TelegramDelivery(client.proxy) { rejected += it })

        assertTrue(rejected.isEmpty(), "a chat-level refusal was mistaken for a dead file_id")
    }

    private suspend fun deliverSticker(delivery: TelegramDelivery) {
        val outbox = BotOutbox().apply { enqueue(BotOutput.Sticker("dead-file-id", catalogId = 42L)) }

        delivery.sendScheduled(
            result = AgentResult(outputs = outbox.pending, comment = null),
            chatId = 1L,
            userId = 2L,
            messages = Messages.of(Language.ENGLISH)
        )
    }

    private class StickerRejectingClient(private val description: String) {

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, _ ->
                check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }

                CompletableFuture.failedFuture<Any>(
                    TelegramApiRequestException(
                        "Error executing request",
                        ApiResponse.builder<Serializable>()
                            .ok(false)
                            .errorCode(400)
                            .errorDescription(description)
                            .build()
                    )
                )
            } as TelegramClient
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
