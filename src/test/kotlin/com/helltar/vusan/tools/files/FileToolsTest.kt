package com.helltar.vusan.tools.files

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// a literal address keeps the private-address guard on its real code path without a DNS lookup
private const val PUBLIC_HOST = "93.184.216.34"

class FileToolsTest {

    private fun tools(outbox: BotOutbox, handler: MockRequestHandler = { respond("") }) =
        FileTools(FileDownloadClient(Http.createClient(MockEngine(handler))), outbox)

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

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual), "document bytes differ from the downloaded payload")
    }
}
