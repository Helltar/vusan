package com.helltar.vusan.tools.workspace

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.RequestContext
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WorkspaceToolsTest {
    private val context = RequestContext(chatId = 55L, userId = 55L, messageId = 1L, chatIsPrivate = true)
    private val writes = mutableListOf<Pair<String, ByteArray>>()

    private fun tools(
        result: String = """{"jobId":"d6e07bfb-61dd-469a-94ad-2d05e1a19493","status":"completed","exitCode":0}""",
        status: HttpStatusCode = HttpStatusCode.OK,
        files: Map<String, ByteArray> = emptyMap(),
        attached: AttachedFile? = null,
        outbox: BotOutbox = BotOutbox()
    ): WorkspaceTools {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val wanted = request.url.parameters["path"].orEmpty()
            assertEquals("u55", request.url.parameters["id"])
            when {
                path.startsWith("/jobs") -> respond(result, status, headersOf(HttpHeaders.ContentType, "application/json"))
                path == "/files" && request.method == HttpMethod.Put -> {
                    writes += wanted to request.body.toByteArray()
                    respond("{}", HttpStatusCode.OK)
                }
                path == "/files" -> files[wanted]?.let { respond(it, HttpStatusCode.OK) }
                    ?: respond("""{"error":"No such path"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("Unexpected request")
            }
        }
        return WorkspaceTools(WorkspaceClient(Http.createClient(engine), "http://workspace:8080", 600.seconds, "test-token"), context, outbox, attached)
    }

    @Test
    fun `command output and failed exit code are visible`() = runBlocking {
        val result = tools(result = """{"jobId":"d6e07bfb-61dd-469a-94ad-2d05e1a19493","status":"completed","exitCode":2,"output":"no such recipe"}""")
            .runCommand("make recipe")
        assertContains(result, "<command_output>")
        assertContains(result, "no such recipe")
        assertContains(result, "Exit code 2.")
    }

    @Test
    fun `running commands expose the id and continuation offset`() = runBlocking {
        val result = tools(result = """{"jobId":"d6e07bfb-61dd-469a-94ad-2d05e1a19493","status":"running","output":"building","nextOffset":8}""")
            .runCommand("make")
        assertContains(result, "d6e07bfb-61dd-469a-94ad-2d05e1a19493")
        assertContains(result, "readWorkspaceCommand")
        assertContains(result, "offset=8")
    }

    @Test
    fun `timeouts report stopped processes and retained files`() = runBlocking {
        val result = tools(result = """{"jobId":"d6e07bfb-61dd-469a-94ad-2d05e1a19493","status":"timed_out"}""").runCommand("sleep 900", 5)
        assertContains(result, "timed_out")
        assertContains(result, "files were kept")
        assertTrue("setsid" !in result)
    }

    @Test
    fun `truncated logs never claim the full output was retained`() = runBlocking {
        val result = tools(result = """{"jobId":"d6e07bfb-61dd-469a-94ad-2d05e1a19493","status":"completed","truncated":true}""").runCommand("make")
        assertContains(result, "later output was discarded")
    }

    @Test
    fun `capacity refusal reaches the model`() = runBlocking {
        val result = tools(result = """{"error":"The workspace service is at capacity"}""", status = HttpStatusCode.Conflict).runCommand("ls")
        assertContains(result, "at capacity")
    }

    @Test
    fun `attachments get unique paths and are copied once per turn`() = runBlocking {
        val attached = AttachedFile(
            name = "orders.csv", fileSizeBytes = 9, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { "id,total\n".toByteArray() }
        )
        val firstTurn = tools(attached = attached)
        val result = firstTurn.runCommand("ls inbox")
        val firstPath = writes.single().first
        assertTrue(firstPath.startsWith("inbox/") && firstPath.endsWith("/orders.csv"))
        assertContains(result, firstPath)
        firstTurn.runCommand("ls inbox")
        assertEquals(1, writes.size)
        tools(attached = attached).writeWorkspaceFile("notes.txt", "review the totals")
        assertNotEquals(firstPath, writes[1].first)
    }

    @Test
    fun `file writing reports the imported attachment path`() = runBlocking {
        val attached = AttachedFile(
            name = "table.csv", fileSizeBytes = 1, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { byteArrayOf(1) }
        )
        val result = tools(attached = attached).writeWorkspaceFile("script.py", "print(1)")
        assertContains(result, writes.first().first)
    }

    @Test
    fun `sending picks media kinds and reports missing files`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(files = mapOf("cover.png" to byteArrayOf(1), "project.zip" to byteArrayOf(2)), outbox = outbox)
            .sendFromWorkspace(listOf("cover.png", "project.zip", "missing.txt"))
        val queued = outbox.pending.map { it.output }
        assertIs<BotOutput.Photo>(queued.first { it is BotOutput.Photo })
        assertEquals("project.zip", queued.filterIsInstance<BotOutput.Document>().single().filename)
        assertContains(result, "Not sent")
        assertContains(result, "missing.txt")
    }

    @Test
    fun `recent commands can be rediscovered after a conversation is cleared`() = runBlocking {
        val result = tools(result = """{"jobs":[{"jobId":"d6e07bfb-61dd-469a-94ad-2d05e1a19493","status":"interrupted"}]}""")
            .readWorkspaceCommand()
        assertContains(result, "d6e07bfb-61dd-469a-94ad-2d05e1a19493: interrupted")
    }
}
