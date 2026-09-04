package com.helltar.vusan.tools.workspace

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.RequestContext
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class WorkspaceToolsTest {

    private val context = RequestContext(chatId = 55L, userId = 55L, messageId = 1L, chatIsPrivate = true)
    private val writes = mutableListOf<Pair<String, ByteArray>>()

    private fun tools(
        exec: String = """{"exitCode":0,"stdout":"","stderr":""}""",
        files: Map<String, ByteArray> = emptyMap(),
        attached: AttachedFile? = null,
        outbox: BotOutbox = BotOutbox()
    ): WorkspaceTools {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val wanted = request.url.parameters["path"].orEmpty()

            when {
                path == "/exec" ->
                    respond(exec, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

                path == "/files" && request.method == HttpMethod.Put -> {
                    writes += wanted to request.body.toByteArray()
                    respond("""{"path":"$wanted"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                path == "/files" ->
                    files[wanted]
                        ?.let { respond(it, HttpStatusCode.OK) }
                        ?: respond("""{"error":"No such path"}""", HttpStatusCode.BadRequest)

                else -> respond("""{"error":"unexpected ${request.method.value} $path"}""", HttpStatusCode.NotFound)
            }
        }

        val client = WorkspaceClient(Http.createClient(engine), "http://workspace:8080", 600.seconds)
        return WorkspaceTools(client, context, outbox, attached)
    }

    @Test
    fun `stdout comes back in its own block`() = runBlocking {
        val result = tools(exec = """{"exitCode":0,"stdout":"seven crates counted","stderr":""}""")
            .runCommand("ls", 0)

        assertContains(result, "<stdout>")
        assertContains(result, "seven crates counted")
    }

    @Test
    fun `a failing command reports its exit code`() = runBlocking {
        val result = tools(exec = """{"exitCode":2,"stdout":"","stderr":"no such recipe"}""")
            .runCommand("make cake", 0)

        assertContains(result, "Exit code 2.")
        assertContains(result, "no such recipe")
    }

    @Test
    fun `a timeout tells the model how to keep the work running`() = runBlocking {
        val result = tools(exec = """{"exitCode":143,"timedOut":true,"stdout":"","stderr":""}""")
            .runCommand("sleep 900", 5)

        assertContains(result, "time limit")
        assertContains(result, "setsid")
        // the exit code of a killed command is noise next to the timeout itself
        assertTrue("Exit code" !in result)
    }

    @Test
    fun `truncated output points at the log instead of inviting a rerun`() = runBlocking {
        val result = tools(
            exec = """{"exitCode":0,"stdout":"partial","stdoutTruncated":true,"logPath":".vusan/logs/ab12cd34.log"}"""
        ).runCommand("./build.sh", 0)

        assertContains(result, ".vusan/logs/ab12cd34.log")
        assertContains(result, "grep")
    }

    @Test
    fun `a service refusal is passed through untouched`() = runBlocking {
        val result = tools(exec = """{"error":"This workspace is already running a command."}""")
            .runCommand("ls", 0)

        assertEquals("This workspace is already running a command.", result)
    }

    @Test
    fun `an attachment lands in inbox before the first command runs`() = runBlocking {
        val attached = AttachedFile(
            name = "orders.csv",
            fileSizeBytes = 12,
            mimeType = "text/csv",
            kind = AttachedFileKind.OTHER,
            loadBytes = { "id,total\n".toByteArray() }
        )

        val workspace = tools(attached = attached)
        val first = workspace.runCommand("ls inbox", 0)

        assertContains(first, "inbox/orders.csv")
        assertEquals("inbox/orders.csv", writes.single().first)

        // and only once: a second command must not re-upload it or repeat the note
        val second = workspace.runCommand("ls inbox", 0)
        assertTrue("inbox/orders.csv" !in second)
        assertEquals(1, writes.size)
    }

    @Test
    fun `sending picks the output kind from the extension`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(
            files = mapOf("art/cover.png" to byteArrayOf(1, 2, 3), "game.zip" to byteArrayOf(4, 5)),
            outbox = outbox
        ).sendFromWorkspace(listOf("art/cover.png", "game.zip"))

        val queued = outbox.pending.map { it.output }
        assertIs<BotOutput.Photo>(queued.first { it is BotOutput.Photo })
        assertEquals("game.zip", queued.filterIsInstance<BotOutput.Document>().single().filename)
        assertContains(result, "cover.png")
    }

    @Test
    fun `a file that cannot be read is reported rather than silently dropped`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(files = mapOf("there.txt" to byteArrayOf(9)), outbox = outbox)
            .sendFromWorkspace(listOf("there.txt", "gone.txt"))

        assertEquals(1, outbox.pending.size)
        assertContains(result, "Not sent")
        assertContains(result, "gone.txt")
    }
}
