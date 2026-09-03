package com.helltar.vusan.tools.files

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import io.ktor.client.engine.mock.*
import io.ktor.http.*
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

// a literal address keeps the private-address guard on its real code path without a DNS lookup
private const val PUBLIC_HOST = "93.184.216.34"

class FileToolsTest {

    private fun tools(
        outbox: BotOutbox,
        telegram: TelegramClient = FakeTelegramFiles().proxy,
        handler: MockRequestHandler = { respond("") }
    ) = FileTools(FileDownloadClient(Http.createClient(MockEngine(handler))), telegram, outbox)

    @Test
    fun `stores markdown as document in outbox`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        val content = "# Article\n\nHello world."
        tools.sendFile(content = content, filename = "article.md")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("article.md", doc.filename)
        assertEquals(content, doc.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `sanitizes filename with path traversal and forbidden chars`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.sendFile(content = "x", filename = "../../etc/pa<ss>wd:bad?.txt")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("pa_ss_wd_bad_.txt", doc.filename)
    }

    @Test
    fun `falls back to default filename when sanitized is blank`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.sendFile(content = "x", filename = "...")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("file.txt", doc.filename)
    }

    @Test
    fun `queues a downloaded url as a document`() = runBlocking {
        val outbox = BotOutbox()
        val payload = ByteArray(2048) { 7 }

        val tools =
            tools(outbox) {
                respond(content = payload, headers = headersOf(HttpHeaders.ContentType, "application/pdf"))
            }

        val reply = tools.downloadFile(url = "https://$PUBLIC_HOST/docs/report.pdf")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("report.pdf", doc.filename)
        assertContentEqualsBytes(payload, doc.bytes)
        assertContains(reply, "report.pdf")
        assertContains(reply, "2 KB")
    }

    @Test
    fun `reports an oversize file instead of queuing it`() = runBlocking {
        val outbox = BotOutbox()

        val tools =
            tools(outbox) {
                respond(content = ByteArray(64), headers = headersOf(HttpHeaders.ContentLength, "104857600"))
            }

        val reply = tools.downloadFile(url = "https://$PUBLIC_HOST/big.iso")

        assertTrue(outbox.pending.isEmpty())
        assertContains(reply, "100.0 MB")
        assertContains(reply, "$MAX_DOWNLOAD_MB MB Telegram upload limit")
    }

    @Test
    fun `reports a refused local address as a tool failure`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox) { respond(content = "secret") }

        val reply = tools.downloadFile(url = "http://127.0.0.1:9090/metrics")

        assertTrue(outbox.pending.isEmpty())
        assertContains(reply, "Tool failed")
        assertContains(reply, "private or local address")
    }

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
