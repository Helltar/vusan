package com.helltar.vusan.telegram

import com.helltar.vusan.outbox.BotOutput
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.ApiException
import dev.inmo.tgbotapi.bot.exceptions.InvalidPhotoDimensionsException
import dev.inmo.tgbotapi.requests.abstracts.MultipartRequest
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.types.Response
import dev.inmo.tgbotapi.types.message.abstracts.PrivateContentMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramOutputSenderTest {

    @Test
    fun `photo falls back to document by default`() = runBlocking {
        val bot = RecordingBot(failPhoto = true)

        TelegramOutputSender.send(
            bot = bot,
            item = BotOutput.Photo(byteArrayOf(1, 2, 3), "chart.bmp"),
            chatId = 1L.toChatIdentifier(),
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendPhoto", "sendDocument"), bot.methods)
    }

    @Test
    fun `photo can skip document fallback when document copy is queued separately`() = runBlocking {
        val bot = RecordingBot(failPhoto = true)

        TelegramOutputSender.send(
            bot = bot,
            item = BotOutput.Photo(
                bytes = byteArrayOf(1, 2, 3),
                filename = "chart.bmp",
                fallbackToDocument = false
            ),
            chatId = 1L.toChatIdentifier(),
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendPhoto"), bot.methods)
    }

    @Test
    fun `caption with rejected formatting is resent captionless plus a document`() = runBlocking {
        val bot = RecordingBot(failHtmlCaptionOnce = true)

        TelegramOutputSender.send(
            bot = bot,
            item = BotOutput.Photo(byteArrayOf(1, 2, 3), "chart.png"),
            chatId = 1L.toChatIdentifier(),
            replyParameters = null,
            caption = "<b>broken",
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendPhoto", "sendPhoto", "sendDocument"), bot.methods)
    }

    @Test
    fun `text reply with rejected formatting is sent as a document`() = runBlocking {
        val bot = RecordingBot(failHtmlText = true)

        TelegramOutputSender.sendReplyText(
            bot = bot,
            chatId = 1L.toChatIdentifier(),
            text = "<b>broken",
            replyParameters = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendMessage", "sendDocument"), bot.methods)
    }

    @Test
    fun `video thumbnail is sent as both thumbnail and message cover`() = runBlocking {
        val bot = RecordingBot()

        TelegramOutputSender.send(
            bot = bot,
            item = BotOutput.Video(
                bytes = byteArrayOf(1, 2, 3),
                filename = "video.mp4",
                thumbnail = byteArrayOf(4, 5, 6)
            ),
            chatId = 1L.toChatIdentifier(),
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        val request = assertIs<MultipartRequest<*>>(bot.requests.single())
        val thumbnailFile = request.mediaMap.values.single { it.filename == "thumbnail.jpg" }
        val coverFile = request.mediaMap.values.single { it.filename == "cover.jpg" }

        assertEquals(listOf("cover.jpg", "thumbnail.jpg", "video.mp4"), request.mediaMap.values.map { it.filename }.sorted())
        assertEquals(thumbnailFile.fileId, request.paramsJson["thumbnail"]?.jsonPrimitive?.content)
        assertEquals("attach://${coverFile.fileId}", request.paramsJson["cover"]?.jsonPrimitive?.content)
    }

    @Test
    fun `rich message is sent via sendRichMessage`() = runBlocking {
        val bot = RecordingBot()

        TelegramOutputSender.send(
            bot = bot,
            item = BotOutput.RichMessage("# Title\n\n- one\n- two"),
            chatId = 1L.toChatIdentifier(),
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendRichMessage"), bot.methods)
    }

    @Test
    fun `rejected rich message is resent as a markdown document`() = runBlocking {
        val bot = RecordingBot(failRichMessage = true)

        TelegramOutputSender.send(
            bot = bot,
            item = BotOutput.RichMessage("# Title"),
            chatId = 1L.toChatIdentifier(),
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendRichMessage", "sendDocument"), bot.methods)
        val document = assertIs<MultipartRequest<*>>(bot.requests.last())
        assertEquals("message.md", document.mediaMap.values.single().filename)
    }

    @Test
    fun `rich markdown document fallback drops to plain text when the document also fails`() = runBlocking {
        val bot = RecordingBot(failRichMessage = true, failDocument = true)

        TelegramOutputSender.send(
            bot = bot,
            item = BotOutput.RichMessage("# Title"),
            chatId = 1L.toChatIdentifier(),
            replyParameters = null,
            caption = null,
            formattingFileNotice = "notice"
        )

        assertEquals(listOf("sendRichMessage", "sendDocument", "sendMessage"), bot.methods)
    }

    private class RecordingBot(
        private val failPhoto: Boolean = false,
        private val failHtmlText: Boolean = false,
        private var failHtmlCaptionOnce: Boolean = false,
        private val failRichMessage: Boolean = false,
        private val failDocument: Boolean = false
    ) : TelegramBot {
        val methods = mutableListOf<String>()
        val requests = mutableListOf<Request<*>>()

        override suspend fun <T : Any> execute(request: Request<T>): T {
            val method = request.method()
            methods += method
            requests += request

            // the first sendPhoto carries the html caption; the captionless retry succeeds.
            if (failHtmlCaptionOnce && method == "sendPhoto") {
                failHtmlCaptionOnce = false
                throw ApiException(
                    httpResponseCode = 400,
                    plainResponse = """{"description":"Bad Request: can't parse entities"}""",
                    response = Response(description = "Bad Request: can't parse entities")
                )
            }

            if (failRichMessage && method == "sendRichMessage") {
                throw ApiException(
                    httpResponseCode = 400,
                    plainResponse = """{"description":"Bad Request: can't parse entities"}""",
                    response = Response(description = "Bad Request: can't parse entities")
                )
            }

            if (failDocument && method == "sendDocument") {
                throw ApiException(
                    httpResponseCode = 400,
                    plainResponse = """{"description":"Bad Request: file too big"}""",
                    response = Response(description = "Bad Request: file too big")
                )
            }

            if (failPhoto && method == "sendPhoto") {
                throw InvalidPhotoDimensionsException(
                    response = Response(description = "PHOTO_INVALID_DIMENSIONS"),
                    plainAnswer = """{"description":"PHOTO_INVALID_DIMENSIONS"}""",
                    message = null,
                    cause = null
                )
            }

            if (failHtmlText && method == "sendMessage") {
                throw ApiException(
                    httpResponseCode = 400,
                    plainResponse = """{"description":"Bad Request: can't parse entities"}""",
                    response = Response(description = "Bad Request: can't parse entities")
                )
            }

            @Suppress("UNCHECKED_CAST")
            return dummyContentMessage() as T
        }

        override fun close() = Unit
    }
}

private fun dummyContentMessage(): PrivateContentMessage<*> =
    Proxy.newProxyInstance(
        PrivateContentMessage::class.java.classLoader,
        arrayOf(PrivateContentMessage::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "getHasProtectedContent" -> false
            "toString" -> "DummyContentMessage"
            "hashCode" -> 0
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected dummy message access: ${method.name}")
        }
    } as PrivateContentMessage<*>
