package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SandboxToolsTest {

    private fun tools(outbox: BotOutbox, responseJson: String): SandboxTools {
        val http =
            Http.createClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/run", request.url.encodedPath)
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        return SandboxTools(SandboxClient(http, "http://sandbox:8080", 30.seconds), outbox)
    }

    @Test
    fun `chart png is sent as a photo and stdout is returned`() = runBlocking {
        val outbox = BotOutbox()
        val pngBytes = byteArrayOf(1, 2, 3)
        val base64 = java.util.Base64.getEncoder().encodeToString(pngBytes)
        val result =
            tools(outbox, """{"ok":true,"stdout":"chart done\n","files":[{"name":"chart.png","base64":"$base64"}]}""")
                .runCode("print('chart done')")

        val photo = assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertEquals("chart.png", photo.filename)
        assertContentEquals(pngBytes, photo.bytes)

        assertContains(result, "<stdout>")
        assertContains(result, "chart done")
        assertContains(result, "Delivered 1 file(s) to the chat: chart.png")
    }

    @Test
    fun `multiple images are sent as a photo group`() = runBlocking {
        val outbox = BotOutbox()
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9))
        tools(
            outbox,
            """{"ok":true,"files":[{"name":"a.png","base64":"$b64"},{"name":"b.png","base64":"$b64"}]}"""
        ).runCode("...")

        val group = assertIs<BotOutput.PhotoGroup>(outbox.pending.single().output)
        assertEquals(2, group.photos.size)
    }

    @Test
    fun `gif is sent as an animation, not a photo`() = runBlocking {
        val outbox = BotOutbox()
        val bytes = byteArrayOf(7, 7, 7)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        tools(outbox, """{"ok":true,"files":[{"name":"lorenz.gif","base64":"$b64"}]}""").runCode("...")

        val animation = assertIs<BotOutput.Animation>(outbox.pending.single().output)
        assertEquals("lorenz.gif", animation.filename)
        assertContentEquals(bytes, animation.bytes)
    }

    @Test
    fun `non-image file is sent as a document`() = runBlocking {
        val outbox = BotOutbox()
        val b64 = java.util.Base64.getEncoder().encodeToString("a,b\n1,2".toByteArray())
        tools(outbox, """{"ok":true,"files":[{"name":"data.csv","base64":"$b64"}]}""").runCode("...")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("data.csv", doc.filename)
    }

    @Test
    fun `runtime error returns the meaningful last traceback line`() = runBlocking {
        val outbox = BotOutbox()
        val error = "Traceback (most recent call last):\\n  File \\\"<exec>\\\", line 1\\nZeroDivisionError: division by zero\\n"
        val result =
            tools(outbox, """{"ok":false,"error":"$error"}""").runCode("print(1/0)")

        assertContains(result, "raised an error: ZeroDivisionError: division by zero")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `timeout returns a hint and enqueues nothing`() = runBlocking {
        val outbox = BotOutbox()
        val result =
            tools(outbox, """{"ok":false,"timedOut":true,"error":"execution timed out"}""").runCode("while True: pass")

        assertContains(result, "timed out")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `successful run with no output nudges to print`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(outbox, """{"ok":true}""").runCode("x = 1")

        assertContains(result, "no output")
        assertTrue(outbox.pending.isEmpty())
    }
}
