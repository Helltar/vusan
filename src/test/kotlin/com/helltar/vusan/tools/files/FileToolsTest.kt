package com.helltar.vusan.tools.files

import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.BotOutbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class FileToolsTest {

    @Test
    fun `stores markdown as document in outbox`() = runBlocking {
        val outbox = BotOutbox()
        val tools = FileTools(outbox)

        val content = "# Article\n\nHello world."
        tools.sendFile(content = content, filename = "article.md")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("article.md", doc.filename)
        assertEquals(content, doc.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `sanitizes filename with path traversal and forbidden chars`() = runBlocking {
        val outbox = BotOutbox()
        val tools = FileTools(outbox)

        tools.sendFile(content = "x", filename = "../../etc/pa<ss>wd:bad?.txt")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("pa_ss_wd_bad_.txt", doc.filename)
    }

    @Test
    fun `falls back to default filename when sanitized is blank`() = runBlocking {
        val outbox = BotOutbox()
        val tools = FileTools(outbox)

        tools.sendFile(content = "x", filename = "...")

        val doc = assertIs<BotOutput.Document>(outbox.pending.single().output)
        assertEquals("file.txt", doc.filename)
    }
}
