package com.helltar.vusan.tools.sandbox

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
private fun recordedOutput(text: String, complete: Boolean, offset: Long, exec: String): String {
    val fresh = if (offset == 0L) text else ""
    val end = offset + fresh.length
    val frames = if (fresh.isEmpty()) "[]" else """[{"kind":"stdout","text":"$fresh","end":$end}]"""

    return """{"frames":$frames,"nextOffset":$end,"complete":$complete,"exec":$exec}"""
}

private fun MockRequestHandleScope.json(body: String) =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

private fun MockRequestHandleScope.problem(status: HttpStatusCode, body: String) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/problem+json"))

class SandboxToolsTest {
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
        outbox: BotOutbox = BotOutbox(),
    ): SandboxTools {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val wanted = request.url.parameters["path"].orEmpty()
            assertTrue(
                path == "/v1/sandboxes" || path.startsWith("/v1/sandboxes/$SANDBOX_ID"),
                "a request left this person's sandbox: $path",
            )
            failure?.let { (status, body) -> return@MockEngine problem(status, body) }
            when {
                path == "/v1/sandboxes" && request.method == HttpMethod.Post -> {
                    creations++
                    assertContains(request.body.toByteArray().decodeToString(), """"alias":"telegram:55"""")
                    json(sandboxInfo("telegram:55"))
                }

                path == "/v1/sandboxes/$SANDBOX_ID" && request.method == HttpMethod.Delete -> {
                    resets++
                    respond("", HttpStatusCode.NoContent)
                }

                path.endsWith("/execs") && request.method == HttpMethod.Post -> json(info)
                path.endsWith("/execs") -> json(execs)
                // the page carries the exec, so reading a command asks for nothing else
                path.endsWith("/output") ->
                    json(recordedOutput(output, complete, request.url.parameters["offset"]?.toLong() ?: 0, info))

                path.endsWith("/cancel") -> json(info)

                path.endsWith("/files/content") && request.method == HttpMethod.Put -> {
                    writes += wanted to request.body.toByteArray()
                    json("""{"path":"/home/sandbox/$wanted","name":"file","type":"file","size":1,"modifiedAt":"2026-09-13T12:00:00Z","mode":420}""")
                }

                path.endsWith("/files/content") -> files[wanted]?.let { respond(it, HttpStatusCode.OK) }
                    ?: problem(HttpStatusCode.NotFound, problemDocument("not_found", 404, "Not found", "No such file"))

                path.endsWith("/files") && request.method == HttpMethod.Delete -> {
                    deletions += wanted
                    respond("", HttpStatusCode.NoContent)
                }

                else -> error("Unexpected request: ${request.method.value} $path")
            }
        }

        return SandboxTools(
            SandboxClient(Http.createClient(engine), "http://regolith:8080", "test-token").sandboxOf(requireNotNull(context.personKeyOrNull)),
            outbox,
            attached,
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
        assertContains(result, "readSandboxCommand")
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
    fun `an interrupted command names why the sandbox stopped`() = runBlocking {
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
        val sandbox = tools(
            failure = HttpStatusCode.ServiceUnavailable to
                problemDocument("capacity_exhausted", 503, "No session capacity", "busy"),
        )
        assertContains(toolFailure { sandbox.runCommand("ls") }, "at capacity")
    }

    @Test
    fun `attachments get unique paths and are copied once per turn`() = runBlocking {
        val attached = AttachedFile(
            name = "orders.csv", fileSizeBytes = 9, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { "id,total\n".toByteArray() },
        )
        val firstTurn = tools(attached = attached)
        val result = firstTurn.runCommand("ls inbox")
        val firstPath = writes.single().first
        assertTrue(firstPath.startsWith("inbox/") && firstPath.endsWith("/orders.csv"))
        assertContains(result, firstPath)
        firstTurn.runCommand("ls inbox")
        assertEquals(1, writes.size)
        tools(attached = attached).writeSandboxFile("notes.txt", "review the totals")
        assertNotEquals(firstPath, writes[1].first)
    }

    @Test
    fun `file writing reports the imported attachment path`() = runBlocking {
        val attached = AttachedFile(
            name = "table.csv", fileSizeBytes = 1, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { byteArrayOf(1) },
        )
        val result = tools(attached = attached).writeSandboxFile("script.py", "print(1)")
        assertContains(result, writes.first().first)
    }

    @Test
    fun `sending picks media kinds and reports missing files`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(files = mapOf("cover.png" to byteArrayOf(1), "project.zip" to byteArrayOf(2)), outbox = outbox)
            .sendFromSandbox(listOf("cover.png", "project.zip", "missing.txt"))
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
            .sendFromSandbox(listOf("cover.png", "notes.txt"))

        assertEquals("notes.txt", assertIs<BotOutput.Document>(outbox.pending.single().output).filename)
        assertContains(result, "does not accept these")
        assertContains(result, "cover.png")
        assertFalse("Sending 2 file" in result)
    }

    @Test
    fun `cleanup deletes one exact path without uploading attachments`() = runBlocking {
        val attached = AttachedFile(
            name = "unused.csv", fileSizeBytes = 1, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { error("Cleanup must not download attachments") },
        )
        val result = tools(attached = attached).deleteSandboxFile("project/build output")
        assertEquals(listOf("project/build output"), deletions)
        assertTrue(writes.isEmpty())
        assertContains(result, "Deleted")
        assertContains(result, "running commands were left alone")
    }

    @Test
    fun `a reset empties the sandbox without touching single paths`() = runBlocking {
        val attached = AttachedFile(
            name = "unused.csv", fileSizeBytes = 1, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { error("A reset must not download attachments") },
        )
        val result = tools(attached = attached).resetSandbox()
        assertEquals(1, resets)
        assertTrue(deletions.isEmpty() && writes.isEmpty())
        assertContains(result, "empty again")
    }

    @Test
    fun `recent commands can be rediscovered after a conversation is cleared`() = runBlocking {
        val page = """{"execs":[${execInfo(outcome = """{"type":"interrupted","reason":"server_restarted"}""")}]}"""
        val result = tools(execs = page).readSandboxCommand()
        assertContains(result, "$JOB: interrupted")
    }

    // the tools hold one sandbox for the turn they were built for, and ask the server for it once:
    // nothing is remembered between turns, so a home deleted by retention is simply made again
    @Test
    fun `the sandbox is opened once for a whole turn, and again after a reset`() = runBlocking {
        val sandbox = tools()
        sandbox.runCommand("ls")
        sandbox.writeSandboxFile("notes.txt", "hello")
        assertEquals(1, creations)

        sandbox.resetSandbox()
        sandbox.runCommand("ls")

        assertEquals(2, creations)
    }

    // the sdk knows the shape of a server-made id, so a made-up one never reaches a request path
    @Test
    fun `a job id the model made up is refused in words it can act on`() = runBlocking {
        assertContains(toolFailure { tools().readSandboxCommand(jobId = "build-1") }, "not an exec id")
        assertContains(toolFailure { tools().cancelSandboxCommand(jobId = "../../info") }, "not an exec id")
    }
}
