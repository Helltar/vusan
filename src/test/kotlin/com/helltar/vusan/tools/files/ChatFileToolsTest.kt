package com.helltar.vusan.tools.files

import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.File
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.ByteArrayInputStream
import java.io.Serializable
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatFileToolsTest {

    private fun tools(outbox: BotOutbox, telegram: TelegramClient = FakeTelegramFiles().proxy) =
        ChatFileTools(telegram, outbox)

    @Test
    fun `queues a telegram file as a document named by the served path`() = runBlocking {
        val outbox = BotOutbox()
        val payload = ByteArray(3072) { 9 }
        val tools = tools(outbox, FakeTelegramFiles(bytes = payload).proxy)

        val reply = tools.sendChatFile(fileId = "CAACAgIAAxkBAAE")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("file_15.webp", doc.filename)
        assertContentEqualsBytes(payload, doc.bytes)
        assertContains(reply, "file_15.webp")
        assertContains(reply, "3 KB")
    }

    @Test
    fun `gives a requested name without an extension the one telegram served`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.sendChatFile(fileId = "CAACAgIAAxkBAAE", filename = "cat sticker")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("cat sticker.webp", doc.filename)
    }

    @Test
    fun `reports a file above the telegram download limit instead of queuing it`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox, FakeTelegramFiles(failure = "Bad Request: file is too big").proxy)

        val reply = tools.sendChatFile(fileId = "BQACAgIAAxkBAAE")

        assertTrue(outbox.pending.isEmpty())
        assertContains(reply, "$MAX_TELEGRAM_FILE_MB MB")
    }

    @Test
    fun `reports a file id telegram does not accept`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox, FakeTelegramFiles(failure = "Bad Request: wrong file identifier").proxy)

        val reply = tools.sendChatFile(fileId = "made-up-id")

        assertTrue(outbox.pending.isEmpty())
        assertContains(reply, "made-up-id")
        assertContains(reply, "file_unique_id")
    }

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual), "document bytes differ from the downloaded payload")
    }

    private class FakeTelegramFiles(
        private val bytes: ByteArray = ByteArray(1024),
        private val path: String? = "stickers/file_15.webp",
        private val failure: String? = null
    ) {

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "executeAsync" -> respond(args.single() as GetFile)
                    "downloadFileAsStream" -> ByteArrayInputStream(bytes)
                    else -> error("unexpected client call: ${method.name}")
                }
            } as TelegramClient

        private fun respond(request: GetFile): CompletableFuture<Any> =
            failure?.let { CompletableFuture.failedFuture(telegramError(it)) }
                ?: CompletableFuture.completedFuture(
                    File().apply {
                        fileId = request.fileId
                        fileUniqueId = "u"
                        filePath = path
                    }
                )

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
