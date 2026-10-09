package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.agent.TurnShelf
import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.personKeyOrNull
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.tools.keepOnShelf
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
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

private val TURN_START: Instant = Instant.parse("2026-10-10T09:30:00Z")

// named the way the shelf names it, in whatever zone the tests run in
private val TURN_DIRECTORY = "turns/" + DateTimeFormatter.ofPattern("yyMMdd-HHmmss").format(TURN_START.atZone(ZoneId.systemDefault()))

private fun directoryListing(names: List<String>): String =
    names.joinToString(",", prefix = "{\"path\":\"/home/sandbox/turns\",\"entries\":[", postfix = "]}") { name ->
        "{\"path\":\"/home/sandbox/turns/$name\",\"name\":\"$name\",\"type\":\"directory\",\"size\":0," +
            "\"modifiedAt\":\"2026-09-13T12:00:00Z\",\"mode\":493}"
    }

private fun MockRequestHandleScope.json(body: String) =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

private fun MockRequestHandleScope.problem(status: HttpStatusCode, body: String) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/problem+json"))

class SandboxToolsTest {
    private val context = requestContext(chatId = 55L, userId = 55L)
    private val writes = mutableListOf<Pair<String, ByteArray>>()
    private val deletes = mutableListOf<String>()
    private var creations = 0
    private var resets = 0

    private fun tools(
        info: String = execInfo(),
        output: String = "",
        complete: Boolean = true,
        execs: String = """{"execs":[]}""",
        failure: Pair<HttpStatusCode, String>? = null,
        files: Map<String, ByteArray> = emptyMap(),
        attached: List<AttachedFile> = emptyList(),
        outbox: BotOutbox = BotOutbox(),
        // the directories already under `turns/`; none at all is a home that never had one
        turnDirectories: List<String>? = null,
        shelf: TurnShelf = TurnShelf(attached, startedAt = TURN_START),
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

                path.endsWith("/files/entries") ->
                    turnDirectories
                        ?.let { names -> json(directoryListing(names)) }
                        ?: problem(HttpStatusCode.NotFound, problemDocument("not_found", 404, "Not found", "No such directory"))

                path.endsWith("/files") && request.method == HttpMethod.Delete -> {
                    deletes += wanted + if (request.url.parameters["recursive"] == "true") " recursively" else ""
                    respond("", HttpStatusCode.NoContent)
                }

                path.endsWith("/files/content") -> files[wanted]?.let { respond(it, HttpStatusCode.OK) }
                    ?: problem(HttpStatusCode.NotFound, problemDocument("not_found", 404, "Not found", "No such file"))

                else -> error("Unexpected request: ${request.method.value} $path")
            }
        }

        val sandbox = SandboxClient(Http.createClient(engine), "http://regolith:8080", "test-token").sandboxOf(requireNotNull(context.personKeyOrNull))
        shelf.connectSandbox(sandbox)

        return SandboxTools(sandbox, outbox, shelf)
    }

    // what another call made this turn is in the sandbox before the command that may want it runs
    @Test
    fun `a command finds what the turn made in the turn's own directory, written once`() = runBlocking {
        val shelf = TurnShelf(startedAt = TURN_START)
        val maker = shelf.open()
        withContext(maker) { keepOnShelf("speech.mp3", byteArrayOf(4, 2)) }
        maker.close("The speech kept as `#1/1`.", "speakWithVoice")

        val tools = tools(shelf = shelf)
        val result = tools.runCommand("ls turns")

        assertEquals(listOf("$TURN_DIRECTORY/01-1-speech.mp3"), writes.map { it.first })
        assertContains(result, "This turn's files are in the sandbox: `$TURN_DIRECTORY/01-1-speech.mp3`.")

        tools.runCommand("ls turns")

        assertEquals(1, writes.size, "a file already copied is not written again")
    }

    // a turn often picks up the one before it, so the last few stay; the rest would fill a fixed-size home
    @Test
    fun `the first copy of a turn keeps the two turns before it and removes the older ones`() = runBlocking {
        tools(turnDirectories = listOf("260101-090000", "260102-090000", "260103-090000", "260104-090000")).runCommand("ls")

        assertEquals(listOf("turns/260101-090000 recursively", "turns/260102-090000 recursively"), deletes)
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
    fun `attachments reach the turn's directory once per turn, and a later turn gets its own`() = runBlocking {
        val attached = AttachedFile(
            name = "orders.csv", fileSizeBytes = 9, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { "id,total\n".toByteArray() },
        )
        val firstTurn = tools(attached = listOf(attached))
        val result = firstTurn.runCommand("ls turns")
        val firstPath = writes.single().first
        assertEquals("$TURN_DIRECTORY/00-1-orders.csv", firstPath)
        assertContains(result, firstPath)
        firstTurn.runCommand("ls turns")
        assertEquals(1, writes.size)
        tools(shelf = TurnShelf(listOf(attached), startedAt = TURN_START.plusSeconds(60))).writeSandboxFile("notes.txt", "review the totals")
        assertNotEquals(firstPath, writes[1].first)
    }

    // two items of one album may carry the same name, so each gets a number of its own.
    @Test
    fun `every attachment of an album is copied, each to its own path`() = runBlocking {
        val album = listOf("photo.jpg", "photo.jpg", "notes.txt").map { name ->
            AttachedFile(
                name = name, fileSizeBytes = 1, mimeType = null, kind = AttachedFileKind.OTHER,
                loadBytes = { byteArrayOf(1) },
            )
        }
        val sandbox = tools(attached = album)
        val result = sandbox.runCommand("ls turns")
        val paths = writes.map { it.first }
        assertEquals(listOf("00-1-photo.jpg", "00-2-photo.jpg", "00-3-notes.txt"), paths.map { it.substringAfterLast('/') })
        paths.forEach { assertContains(result, it) }
        sandbox.writeSandboxFile("notes.md", "done")
        assertEquals(4, writes.size)
    }

    // one file over the limit costs the model that file, not the rest of the album.
    @Test
    fun `an oversized attachment is named while the others still arrive`() = runBlocking {
        val album = listOf(
            AttachedFile(
                name = "huge.bin", fileSizeBytes = 21L * 1024 * 1024, mimeType = null, kind = AttachedFileKind.OTHER,
                loadBytes = { error("An oversized attachment must not be downloaded") },
            ),
            AttachedFile(
                name = "small.txt", fileSizeBytes = 1, mimeType = "text/plain", kind = AttachedFileKind.OTHER,
                loadBytes = { byteArrayOf(1) },
            ),
        )
        val result = tools(attached = album).runCommand("ls turns")
        assertContains(result, "`huge.bin` is over the 20 MB an attachment may bring in")
        assertTrue(writes.single().first.endsWith("/00-2-small.txt"))
        assertContains(result, writes.single().first)
    }

    @Test
    fun `file writing reports the imported attachment path`() = runBlocking {
        val attached = AttachedFile(
            name = "table.csv", fileSizeBytes = 1, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { byteArrayOf(1) },
        )
        val result = tools(attached = listOf(attached)).writeSandboxFile("script.py", "print(1)")
        assertContains(result, writes.first().first)
    }

    @Test
    fun `a file is read with line numbers, by range, and says where to continue`() = runBlocking {
        val files = mapOf("notes.txt" to "one\ntwo\nthree\n".toByteArray())

        val whole = tools(files = files).readSandboxFile("notes.txt")
        assertContains(whole, "lines 1–3 of 3")
        assertContains(whole, "2\ttwo")
        assertFalse("Continue" in whole)

        val range = tools(files = files).readSandboxFile("notes.txt", fromLine = 2, lineCount = 1)
        assertContains(range, "lines 2–2 of 3")
        assertContains(range, "Continue with fromLine=3")
        assertFalse("three" in range)

        assertContains(toolFailure { tools(files = files).readSandboxFile("notes.txt", fromLine = 9) }, "no line 9")
    }

    @Test
    fun `an edit replaces one exact passage and writes the file back`() = runBlocking {
        val files = mapOf("index.html" to "<h1>Hi</h1>\n<p>old</p>\n".toByteArray())

        val result = tools(files = files).editSandboxFile("index.html", "<p>old</p>", "<p>new</p>")

        assertContains(result, "replaced 1 occurrence")
        assertEquals("index.html", writes.single().first)
        assertEquals("<h1>Hi</h1>\n<p>new</p>\n", writes.single().second.decodeToString())
    }

    @Test
    fun `an edit refuses a passage that is missing or ambiguous, unless every occurrence is meant`() = runBlocking {
        val files = mapOf("a.txt" to "x\nx\n".toByteArray())

        assertContains(toolFailure { tools(files = files).editSandboxFile("a.txt", "nope", "y") }, "does not occur")
        assertContains(toolFailure { tools(files = files).editSandboxFile("a.txt", "x", "y") }, "occurs 2 times")
        assertTrue(writes.isEmpty(), "a refused edit writes nothing")

        assertContains(tools(files = files).editSandboxFile("a.txt", "x", "y", replaceAll = true), "replaced 2 occurrence")
        assertEquals("y\ny\n", writes.single().second.decodeToString())
    }

    @Test
    fun `a binary file is neither read nor edited`() = runBlocking {
        val files = mapOf("a.bin" to byteArrayOf(1, 0, 2))

        assertContains(toolFailure { tools(files = files).readSandboxFile("a.bin") }, "not a text file")
        assertContains(toolFailure { tools(files = files).editSandboxFile("a.bin", "a", "b") }, "not a text file")
    }

    @Test
    fun `sending picks media kinds and reports missing files`() = runBlocking {
        val outbox = BotOutbox()
        val files = mapOf("cover.png" to byteArrayOf(1), "clip.gif" to byteArrayOf(3), "project.zip" to byteArrayOf(2))
        val result = tools(files = files, outbox = outbox)
            .sendFromSandbox(listOf("cover.png", "clip.gif", "project.zip", "missing.txt"))
        val queued = outbox.pending.map { it.output }
        assertIs<BotOutput.Photo>(queued.first { it is BotOutput.Photo })
        assertEquals("clip.gif", queued.filterIsInstance<BotOutput.Animation>().single().filename)
        assertEquals("project.zip", queued.filterIsInstance<BotOutput.Document>().single().filename)
        assertContains(result, "Not sent")
        assertContains(result, "missing.txt")
    }

    @Test
    fun `a soundless mp4 goes out as an animation when asked`() = runBlocking {
        val outbox = BotOutbox()
        tools(files = mapOf("reversed.mp4" to byteArrayOf(1)), outbox = outbox)
            .sendFromSandbox(listOf("reversed.mp4"), sendAs = "animation")

        assertEquals("reversed.mp4", assertIs<BotOutput.Animation>(outbox.pending.single().output).filename)
    }

    @Test
    fun `media goes out as plain documents when asked`() = runBlocking {
        val outbox = BotOutbox()
        tools(files = mapOf("cover.png" to byteArrayOf(1), "clip.mp4" to byteArrayOf(2)), outbox = outbox)
            .sendFromSandbox(listOf("cover.png", "clip.mp4"), sendAs = "document")

        val documents = outbox.pending.map { assertIs<BotOutput.Document>(it.output).filename }
        assertEquals(listOf("cover.png", "clip.mp4"), documents)
    }

    @Test
    fun `a kind that does not fit the files is refused and nothing is sent`() = runBlocking {
        val outbox = BotOutbox()
        val sandbox = tools(files = mapOf("clip.webm" to byteArrayOf(1)), outbox = outbox)

        assertContains(toolFailure { sandbox.sendFromSandbox(listOf("clip.webm"), sendAs = "animation") }, "`.gif` and `.mp4`")
        assertContains(toolFailure { sandbox.sendFromSandbox(listOf("clip.webm"), sendAs = "sticker") }, "sendAs must be")
        assertTrue(outbox.pending.isEmpty())
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
    fun `a reset empties the sandbox without touching single paths`() = runBlocking {
        val attached = AttachedFile(
            name = "unused.csv", fileSizeBytes = 1, mimeType = "text/csv", kind = AttachedFileKind.OTHER,
            loadBytes = { error("A reset must not download attachments") },
        )
        val result = tools(attached = listOf(attached)).resetSandbox()
        assertEquals(1, resets)
        assertTrue(writes.isEmpty())
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
