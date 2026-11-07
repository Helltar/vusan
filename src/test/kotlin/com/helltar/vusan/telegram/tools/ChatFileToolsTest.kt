package com.helltar.vusan.telegram.tools

import com.helltar.vusan.agent.TurnShelf
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.PlatformLimits
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.File
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.io.Serializable
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatFileToolsTest {

    private fun tools(
        outbox: BotOutbox,
        telegram: TelegramClient = FakeTelegramFiles().proxy,
        limits: PlatformLimits = PlatformLimits.TELEGRAM,
    ) = ChatFileTools(telegram, outbox, limits)

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

    // a local bot api server answers getFile with a path on its own disk and serves no download: the fake
    // fails any download, so the file can only have come off the disk
    @Test
    fun `reads a file a local bot api server names by its absolute path`() = runBlocking {
        val payload = ByteArray(2048) { 7 }
        val local = createTempDirectory().resolve("videos").createDirectories().resolve("file_3.mp4").apply { writeBytes(payload) }
        val outbox = BotOutbox()
        val tools = tools(outbox, FakeTelegramFiles(path = local.toString(), served = false).proxy, PlatformLimits.TELEGRAM_LOCAL_SERVER)

        tools.sendChatFile(fileId = "BAACAgIAAxkBAAE")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("file_3.mp4", doc.filename)
        assertContentEqualsBytes(payload, doc.bytes)
    }

    @Test
    fun `refuses a local file past the bot's own limit before reading it`() = runBlocking {
        val local = createTempFile(suffix = ".mp4")
        RandomAccessFile(local.toFile(), "rw").use { it.setLength(PlatformLimits.TELEGRAM_LOCAL_SERVER.downloadBytes + 1) }
        val outbox = BotOutbox()
        val tools = tools(outbox, FakeTelegramFiles(path = local.toString(), served = false).proxy, PlatformLimits.TELEGRAM_LOCAL_SERVER)

        val reply = tools.sendChatFile(fileId = "BAACAgIAAxkBAAE")

        assertTrue(outbox.pending.isEmpty())
        assertContains(reply, "at most 100 MB")
        local.deleteExisting()
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
        assertContains(reply, "at most 20 MB")
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

    // fetching for a chat that refuses documents would be dropped at the send, so it is answered first; kept
    // for a later call, the file is fetched and queued nowhere
    @Test
    fun `a chat that refuses documents is answered before the fetch, unless the file is kept instead`() = runBlocking {
        val outbox = BotOutbox(ChatCapabilities(documents = false))
        val tools = tools(outbox)

        assertContains(tools.sendChatFile(fileId = "CAACAgIAAxkBAAE"), "does not accept documents")

        val kept = withContext(TurnShelf().open()) { tools.sendChatFile(fileId = "CAACAgIAAxkBAAE", send = false) }

        assertContains(kept, "`#1/1`")
        assertTrue(outbox.pending.isEmpty())
    }

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual), "document bytes differ from the downloaded payload")
    }

    private class FakeTelegramFiles(
        private val bytes: ByteArray = ByteArray(1024),
        private val path: String? = "stickers/file_15.webp",
        private val failure: String? = null,
        // false for a local bot api server, which serves nothing over http
        private val served: Boolean = true,
    ) {

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "executeAsync" -> respond(args.single() as GetFile)
                    "downloadFileAsStream" -> if (served) ByteArrayInputStream(bytes) else error("a local server serves no download")
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
                    },
                )

        private fun telegramError(description: String): TelegramApiRequestException =
            TelegramApiRequestException(
                "Error executing request",
                ApiResponse.builder<Serializable>()
                    .ok(false)
                    .errorCode(400)
                    .errorDescription(description)
                    .build(),
            )
    }
}
