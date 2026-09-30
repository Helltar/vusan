package com.helltar.vusan.telegram.tools

import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.toolFailure
import java.io.ByteArrayInputStream
import java.io.Serializable
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.File
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnimationToolsTest {

    @Test
    fun `downloads a video and queues the unchanged mp4 as an animation`() = runBlocking {
        val outbox = BotOutbox()
        val fake = FakeTelegramFiles()

        val result = AnimationTools(fake.proxy, outbox).sendAnimation("video-id")

        val animation = assertIs<BotOutput.Animation>(outbox.pending.single().output)
        assertEquals("file_1.mp4", animation.filename)
        assertContentEquals(fake.bytes, animation.bytes)
        assertEquals("video-id", fake.requestedId)
        assertContains(result, "Queued animation")
    }

    @Test
    fun `queues gif bytes without conversion`() = runBlocking {
        val outbox = BotOutbox()
        val fake = FakeTelegramFiles(path = "animations/file_1.gif")

        AnimationTools(fake.proxy, outbox).sendAnimation("gif-id")

        val animation = assertIs<BotOutput.Animation>(outbox.pending.single().output)
        assertEquals("file_1.gif", animation.filename)
        assertContentEquals(fake.bytes, animation.bytes)
    }

    @Test
    fun `rejects a different served extension`() = runBlocking {
        val outbox = BotOutbox()
        val fake = FakeTelegramFiles(path = "stickers/file_1.webp")

        val failure = toolFailure { AnimationTools(fake.proxy, outbox).sendAnimation("sticker-id") }

        assertContains(failure, "GIF or a silent H.264 MP4")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `rejects blank file id without contacting telegram`() = runBlocking {
        val outbox = BotOutbox()
        val fake = FakeTelegramFiles()

        val failure = toolFailure { AnimationTools(fake.proxy, outbox).sendAnimation(" ") }

        assertContains(failure, "must not be empty")
        assertEquals(null, fake.requestedId)
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `download failure leaves the outbox empty`() = runBlocking {
        val outbox = BotOutbox()
        val fake = FakeTelegramFiles(failure = telegramError("Bad Request: file is too big"))

        val failure = toolFailure { AnimationTools(fake.proxy, outbox).sendAnimation("large-id") }

        assertContains(failure, "file is too big")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `cancellation is not converted into a tool failure`() = runBlocking {
        val outbox = BotOutbox()
        val fake = FakeTelegramFiles(failure = CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            AnimationTools(fake.proxy, outbox).sendAnimation("video-id")
        }
        assertTrue(outbox.pending.isEmpty())
    }

    private fun telegramError(description: String): TelegramApiRequestException =
        TelegramApiRequestException(
            "Error executing request",
            ApiResponse.builder<Serializable>()
                .ok(false)
                .errorCode(400)
                .errorDescription(description)
                .build(),
        )

    private class FakeTelegramFiles(
        val bytes: ByteArray = ByteArray(1024) { 9 },
        private val path: String = "videos/file_1.mp4",
        private val failure: Throwable? = null,
    ) {

        var requestedId: String? = null
            private set

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "executeAsync" -> respond(args.single() as GetFile)
                    "downloadFileAsStream" -> ByteArrayInputStream(bytes)
                    else -> error("unexpected client call: ${method.name}")
                }
            } as TelegramClient

        private fun respond(request: GetFile): CompletableFuture<File> {
            requestedId = request.fileId

            return failure?.let { CompletableFuture.failedFuture(it) }
                ?: CompletableFuture.completedFuture(
                    File().apply {
                        fileId = request.fileId
                        fileUniqueId = "unique"
                        filePath = path
                    },
                )
        }
    }
}
