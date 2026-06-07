package com.helltar.vusan.telegram

import com.helltar.vusan.outbox.BotOutput
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.InvalidPhotoDimensionsException
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.types.Response
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramOutputSenderTest {

    @Test
    fun `photo falls back to document by default`() = runBlocking {
        val bot = RecordingBot(failPhoto = true)

        TelegramOutputSender.send(
            bot = bot,
            item = BotOutput.Photo(byteArrayOf(1, 2, 3), "chart.bmp"),
            chatId = 1L.toChatIdentifier(),
            replyParameters = null,
            caption = null
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
            caption = null
        )

        assertEquals(listOf("sendPhoto"), bot.methods)
    }

    private class RecordingBot(private val failPhoto: Boolean) : TelegramBot {
        val methods = mutableListOf<String>()

        override suspend fun <T : Any> execute(request: Request<T>): T {
            val method = request.method()
            methods += method

            if (failPhoto && method == "sendPhoto") {
                throw InvalidPhotoDimensionsException(
                    response = Response(description = "PHOTO_INVALID_DIMENSIONS"),
                    plainAnswer = """{"description":"PHOTO_INVALID_DIMENSIONS"}""",
                    message = null,
                    cause = null
                )
            }

            @Suppress("UNCHECKED_CAST")
            return dummyContentMessage() as T
        }

        override fun close() = Unit
    }
}

private fun dummyContentMessage(): ContentMessage<*> =
    Proxy.newProxyInstance(
        ContentMessage::class.java.classLoader,
        arrayOf(ContentMessage::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "getHasProtectedContent" -> false
            "toString" -> "DummyContentMessage"
            "hashCode" -> 0
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected dummy message access: ${method.name}")
        }
    } as ContentMessage<*>
