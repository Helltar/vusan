package com.helltar.vusan.tools.workspace

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.personKeyOrNull
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val JOB = "9b7f0d2c4e6a418d93f5c0b1a2d3e4f5"
private const val EXITED = """{"type":"exited","exitCode":0}"""

private fun execInfo(status: String = "finished", outcome: String? = EXITED, truncated: Boolean = false): String =
    """{"id":"$JOB","status":"$status"""" +
        (outcome?.let { ""","outcome":$it""" } ?: "") +
        ""","startedAt":"2026-09-13T12:00:00Z","outputEnd":0,"outputTruncated":$truncated,"stdinOpen":false}"""

/** The recorded output from [offset]: everything on the first read, nothing to add on the next. */
private fun outputPage(text: String, complete: Boolean, offset: Long): String {
    val fresh = if (offset == 0L) text else ""
    val end = offset + fresh.length
    val frames = if (fresh.isEmpty()) "[]" else """[{"kind":"stdout","text":"$fresh","end":$end}]"""
    return """{"frames":$frames,"nextOffset":$end,"complete":$complete}"""
}

private fun MockRequestHandleScope.json(body: String) =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

private fun MockRequestHandleScope.problem(status: HttpStatusCode, body: String) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/problem+json"))

class WorkspaceToolsTest {
    private val context = requestContext(chatId = 55L, userId = 55L)
    private val deletions = mutableListOf<String>()
    private val writes = mutableListOf<Pair<String, ByteArray>>()
    private var creations = 0
    private var resets = 0

    private fun tools(
        info: String = execInfo(),
        output: String = "",
        complete: Boolean = true,
        execs: String = """{"execs":[]}""",
        failure: Pair<HttpStatusCode, String>? = null,
        files: Map<String, ByteArray> = emptyMap(),
        attached: AttachedFile? = null,
        outbox: BotOutbox = BotOutbox()
    ): WorkspaceTools {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val wanted = request.url.parameters["path"].orEmpty()
            assertTrue(path.startsWith("/v1/sandboxes/u55"), "a request left this person's sandbox: $path")
            failure?.let { (status, body) -> return@MockEngine problem(status, body) }
            when {
                path == "/v1/sandboxes/u55" && request.method == HttpMethod.Put -> {
                    creations++
                    json("""{"name":"u55"}""")
                }

                path == "/v1/sandboxes/u55" && request.method == HttpMethod.Delete -> {
                    resets++
                    respond("", HttpStatusCode.NoContent)
                }

                path.endsWith("/execs") && request.method == HttpMethod.Post -> json(info)
                path.endsWith("/execs") -> json(execs)
                path.endsWith("/output") ->
                    json(outputPage(output, complete, request.url.parameters["offset"]?.toLong() ?: 0))

                path.endsWith("/cancel") -> json(info)
                path.contains("/execs/") -> json(info)

                path.endsWith("/files/content") && request.method == HttpMethod.Put -> {
                    writes += wanted to request.body.toByteArray()
                    json("""{"path":"/home/sandbox/$wanted","name":"file","type":"file","size":1,"modifiedAt":"2026-09-13T12:00:00Z","mode":420}""")
                }

                path.endsWith("/files/content") -> files[wanted]?.let { respond(it, HttpStatusCode.OK) }
                    ?: problem(HttpStatusCode.NotFound, """{"code":"not_found","detail":"No such file","title":"Not found","status":404}""")

                path.endsWith("/files") && request.method == HttpMethod.Delete -> {
                    deletions += wanted
                    respond("", HttpStatusCode.NoContent)
                }

                else -> error("Unexpected request: ${request.method.value} $path")
            }
        }
        return WorkspaceTools(
            WorkspaceClient(Http.createClient(engine), "http://regolith:8080", "test-token"),
            requireNotNull(context.personKeyOrNull),
            outbox,
            attached
        )
    }

    @Test
    fun `command output and failed exit code are visible`() = runBlocking {
        val result = tools(info = execInfo(outcome = """{"type":"exited","exitCode":2}"""), output = "no such recipe")
            .runCommand("make recipe")
        assertContains(result, "<command_output>")
        assertContains(result, "no such recipe")
        assertContains(result, "Exit code 2.")
    }

    @Test
    fun `running commands expose the id and continuation offset`() = runBlocking {
        val result = tools(info = execInfo(status = "running", outcome = null), output = "building", complete = false)
            .runCommand("make")
        assertContains(result, JOB)
        assertContains(result, "readWorkspaceCommand")
        assertContains(result, "offset=8")
    }

    @Test
    fun `timeouts report stopped work and retained files`() = runBlocking {
        val result = tools(info = execInfo(outcome = """{"type":"timed_out"}""")).runCommand("sleep 900", 5)
        assertContains(result, "timed_out")
        assertContains(result, "files were kept")
    }

    // an exit code alone leaves the model guessing; the session limit that caused it is something it can act on
    @Test
    fun `a command killed by a session limit says which limit`() = runBlocking {
        val memory = tools(info = execInfo(outcome = """{"type":"exited","exitCode":137,"reason":"oom_killed"}"""))
            .runCommand("python3 train.py")
        assertContains(memory, "ran out of memory")

        val processes = tools(info = execInfo(outcome = """{"type":"exited","exitCode":1,"reason":"pids_limited"}"""))
            .runCommand("make -j64")
        assertContains(processes, "process limit")
    }

    @Test
    fun `an interrupted command names why the workspace stopped`() = runBlocking {
        val result = tools(info = execInfo(outcome = """{"type":"interrupted","reason":"server_restarted"}"""))
            .runCommand("make")
        assertContains(result, "server_restarted")
        assertContains(result, "files were kept")
    }

    @Test
    fun `truncated logs never claim the full output was retained`() = runBlocking {
        val result = tools(info = execInfo(truncated = true)).runCommand("make")
        assertContains(result, "part of the output was dropped")
    }

    @Test
    fun `capacity refusal reaches the model`() = runBlocking {
        val workspace = tools(
            failure = HttpStatusCode.ServiceUnavailable to
                """{"type":"urn:regolith:error:capacity_exhausted","title":"No session capacity","status":503,"detail":"busy","code":"capacity_exhausted"}"""
        )
        assertContains(toolFailure { workspace.runCommand("ls") }, "at capacity")
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

    // reporting a photo as sent into a chat that drops it leaves the model believing the user can see
    // something nobody sent, so the refusal has to reach it by name.
    @Test
    fun `a file kind the chat refuses is named rather than reported as sent`() = runBlocking {
        val outbox = BotOutbox(ChatCapabilities(photos = false))
        val result = tools(files = mapOf("cover.png" to byteArrayOf(1), "notes.txt" to byteArrayOf(2)), outbox = outbox)
            .sendFromWorkspace(listOf("cover.png", "notes.txt"))

        assertEquals("notes.txt", assertIs<BotOutput.Document>(outbox.pending.single().output).filename)
        assertContains(result, "does not accept these")
        assertContains(result, "cover.png")
        assertFalse("Sending 2 file" in result)
    }

    @Test
    fun `cleanup deletes one exact path without uploading attachments`() = runBlocking {
        val attached = AttachedFile(
            name = "unused.csv", fileSizeBytes = 1, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { error("Cleanup must not download attachments") }
        )
        val result = tools(attached = attached).deleteWorkspaceFile("project/build output")
        assertEquals(listOf("project/build output"), deletions)
        assertTrue(writes.isEmpty())
        assertContains(result, "Deleted")
        assertContains(result, "running commands were left alone")
    }

    @Test
    fun `a reset empties the workspace without touching single paths`() = runBlocking {
        val attached = AttachedFile(
            name = "unused.csv", fileSizeBytes = 1, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { error("A reset must not download attachments") }
        )
        val result = tools(attached = attached).resetWorkspace()
        assertEquals(1, resets)
        assertTrue(deletions.isEmpty() && writes.isEmpty())
        assertContains(result, "empty again")
    }

    @Test
    fun `recent commands can be rediscovered after a conversation is cleared`() = runBlocking {
        val page = """{"execs":[${execInfo(outcome = """{"type":"interrupted","reason":"server_restarted"}""")}]}"""
        val result = tools(execs = page).readWorkspaceCommand()
        assertContains(result, "$JOB: interrupted")
    }

    @Test
    fun `the sandbox is created before the first command and not again`() = runBlocking {
        val workspace = tools()
        workspace.runCommand("ls")
        workspace.writeWorkspaceFile("notes.txt", "hello")
        assertEquals(1, creations)
    }
}
