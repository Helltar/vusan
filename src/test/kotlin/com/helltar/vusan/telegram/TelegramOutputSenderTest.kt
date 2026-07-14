package com.helltar.vusan.telegram

import com.helltar.vusan.outbox.BotOutput
import java.io.Serializable
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient

class TelegramOutputSenderTest {

    @Test
    fun `photo falls back to document by default`() = runBlocking {
        val client = RecordingClient(failPhoto = true)

        TelegramOutputSender.send(
            client = client.proxy,
            item = BotOutput.Photo(byteArrayOf(1, 2, 3), "chart.bmp"),
            chatId = 1L,
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendPhoto", "sendDocument"), client.methods)
    }

    @Test
    fun `photo can skip document fallback when document copy is queued separately`() = runBlocking {
        val client = RecordingClient(failPhoto = true)

        TelegramOutputSender.send(
            client = client.proxy,
            item = BotOutput.Photo(
                bytes = byteArrayOf(1, 2, 3),
                filename = "chart.bmp",
                fallbackToDocument = false
            ),
            chatId = 1L,
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendPhoto"), client.methods)
    }

    @Test
    fun `caption with rejected formatting is resent captionless plus a document`() = runBlocking {
        val client = RecordingClient(failHtmlCaptionOnce = true)

        TelegramOutputSender.send(
            client = client.proxy,
            item = BotOutput.Photo(byteArrayOf(1, 2, 3), "chart.png"),
            chatId = 1L,
            replyParameters = null,
            caption = "<b>broken",
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendPhoto", "sendPhoto", "sendDocument"), client.methods)
    }

    @Test
    fun `text reply with rejected formatting is sent as a document`() = runBlocking {
        val client = RecordingClient(failHtmlText = true)

        TelegramOutputSender.sendReplyText(
            client = client.proxy,
            chatId = 1L,
            text = "<b>broken",
            replyParameters = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendMessage", "sendDocument"), client.methods)
    }

    @Test
    fun `br tags in reply text are replaced with newlines before sending`() = runBlocking {
        val client = RecordingClient()

        TelegramOutputSender.sendReplyText(
            client = client.proxy,
            chatId = 1L,
            text = "one<br>two<br/>three<br />four</br>five<BR/>six",
            replyParameters = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendMessage"), client.methods)
        assertEquals("one\ntwo\nthree\nfour\nfive\nsix", assertIs<SendMessage>(client.requests.single()).text)
    }

    @Test
    fun `br tags in captions are replaced with newlines before sending`() = runBlocking {
        val client = RecordingClient()

        TelegramOutputSender.send(
            client = client.proxy,
            item = BotOutput.Photo(byteArrayOf(1, 2, 3), "chart.png"),
            chatId = 1L,
            replyParameters = null,
            caption = "first line<br/>second line",
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendPhoto"), client.methods)
        assertEquals("first line\nsecond line", assertIs<SendPhoto>(client.requests.single()).caption)
    }

    @Test
    fun `video thumbnail is sent as both thumbnail and message cover`() = runBlocking {
        val client = RecordingClient()

        TelegramOutputSender.send(
            client = client.proxy,
            item = BotOutput.Video(
                bytes = byteArrayOf(1, 2, 3),
                filename = "video.mp4",
                thumbnail = byteArrayOf(4, 5, 6)
            ),
            chatId = 1L,
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        val request = assertIs<SendVideo>(client.requests.single())
        assertEquals("video.mp4", request.video.mediaName)
        assertEquals("thumbnail.jpg", request.thumbnail?.mediaName)
        assertEquals("cover.jpg", request.cover?.mediaName)
    }

    @Test
    fun `rich message is sent via sendRichMessage`() = runBlocking {
        val client = RecordingClient()

        TelegramOutputSender.send(
            client = client.proxy,
            item = BotOutput.RichMessage("# Title\n\n- one\n- two"),
            chatId = 1L,
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendRichMessage"), client.methods)
        assertEquals("# Title\n\n- one\n- two", assertIs<SendRichMessage>(client.requests.single()).richMessage.markdown)
    }

    @Test
    fun `rejected rich message is resent as a markdown document`() = runBlocking {
        val client = RecordingClient(failRichMessage = true)

        TelegramOutputSender.send(
            client = client.proxy,
            item = BotOutput.RichMessage("# Title"),
            chatId = 1L,
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendRichMessage", "sendDocument"), client.methods)
        assertEquals("message.md", assertIs<SendDocument>(client.requests.last()).document.mediaName)
    }

    @Test
    fun `rich markdown document fallback drops to plain text when the document also fails`() = runBlocking {
        val client = RecordingClient(failRichMessage = true, failDocument = true)

        TelegramOutputSender.send(
            client = client.proxy,
            item = BotOutput.RichMessage("# Title"),
            chatId = 1L,
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendRichMessage", "sendDocument", "sendMessage"), client.methods)
    }

    // records every request the sender executes; the production code only ever calls `execute`,
    // so a reflective proxy avoids implementing the whole TelegramClient surface.
    private class RecordingClient(
        private val failPhoto: Boolean = false,
        private val failHtmlText: Boolean = false,
        private var failHtmlCaptionOnce: Boolean = false,
        private val failRichMessage: Boolean = false,
        private val failDocument: Boolean = false
    ) {
        val methods = mutableListOf<String>()
        val requests = mutableListOf<Any>()

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, args ->
                check(method.name == "execute") { "unexpected client call: ${method.name}" }
                handle(args.single())
            } as TelegramClient

        private fun handle(request: Any): Any {
            val method = request.javaClass.simpleName.replaceFirstChar { it.lowercase() }
            methods += method
            requests += request

            failureDescriptionFor(method)?.let { throw telegramError(it) }

            return if (request is SendMediaGroup) arrayListOf<Message>() else Message()
        }

        private fun failureDescriptionFor(method: String): String? =
            when {
                // the first sendPhoto carries the html caption; the captionless retry succeeds.
                failHtmlCaptionOnce && method == "sendPhoto" -> {
                    failHtmlCaptionOnce = false
                    "Bad Request: can't parse entities"
                }

                failRichMessage && method == "sendRichMessage" -> "Bad Request: can't parse entities"
                failDocument && method == "sendDocument" -> "Bad Request: file too big"
                failPhoto && method == "sendPhoto" -> "Bad Request: PHOTO_INVALID_DIMENSIONS"
                failHtmlText && method == "sendMessage" -> "Bad Request: can't parse entities"
                else -> null
            }

        private fun telegramError(description: String): TelegramApiRequestException =
            TelegramApiRequestException(
                "Error executing request",
                ApiResponse.builder<Serializable>()
                    .ok(false)
                    .errorCode(400)
                    .errorDescription(description)
                    .build()
            )
    }
}
