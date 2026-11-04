package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.infra.Http
import com.helltar.vusan.request.personKeyOrNull
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val OLDER = "9a0c4e1b7d2f4a6c8e0b1d3f5a7c9e2b"
private const val NEWER = "1b2c3d4e5f60718293a4b5c6d7e8f901"
private const val JOB = "9b7f0d2c4e6a418d93f5c0b1a2d3e4f5"

private const val RUNNING_EXEC =
    """{"id":"$JOB","status":"running","startedAt":"2026-09-13T12:00:00Z","outputEnd":0,"outputTruncated":false,"stdinOpen":false}"""

private fun MockRequestHandleScope.json(body: String) =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

class SandboxManagementToolsTest {
    private val context = requestContext(chatId = 55L, userId = 55L)
    private val requests = mutableListOf<String>()
    private var sentSnapshotBody: String? = null

    private fun tools(
        exists: Boolean = true,
        state: String = "stopped",
        lastSessionEnd: String? = null,
        execs: String = """{"execs":[]}""",
        snapshots: List<String> = listOf(
            snapshotInfo(OLDER, createdAt = "2026-09-13T12:10:00Z", note = "before the upgrade"),
            snapshotInfo(NEWER, createdAt = "2026-09-14T08:30:00Z"),
        ),
    ): SandboxManagementTools {
        val info = sandboxInfo("telegram:55", state = state, lastSessionEnd = lastSessionEnd)
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += "${request.method.value} $path"
            assertTrue(
                path == "/v1/info" || path == "/v1/sandboxes" || path.startsWith("/v1/sandboxes/$SANDBOX_ID"),
                "a request left this person's sandbox: $path",
            )
            when {
                path == "/v1/sandboxes" && request.method == HttpMethod.Post -> json(info)
                path == "/v1/sandboxes" -> json(if (exists) sandboxPage(info) else sandboxPage())
                path == "/v1/sandboxes/$SANDBOX_ID" -> json(info)
                path == "/v1/info" -> json(serverInfo(maxSnapshots = 5))
                path.endsWith("/execs") -> json(execs)
                path.endsWith("/snapshots") && request.method == HttpMethod.Post -> {
                    sentSnapshotBody = request.body.toByteArray().decodeToString()
                    json(snapshotInfo(NEWER, note = "after the tests pass"))
                }

                path.endsWith("/snapshots") -> json(snapshots.joinToString(",", prefix = """{"snapshots":[""", postfix = "]}"))
                path.endsWith("/restore") -> json(info)
                path.contains("/snapshots/") && request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                path.endsWith("/stop") -> json(sandboxInfo("telegram:55"))
                else -> error("Unexpected request: ${request.method.value} $path")
            }
        }
        val client = SandboxClient(Http.createClient(engine), "http://regolith:8080", "test-token")

        return SandboxManagementTools(client.sandboxOf(requireNotNull(context.personKeyOrNull)))
    }

    @Test
    fun `status says when the files go and lists the snapshots with their notes`() = runBlocking {
        val status = tools(lastSessionEnd = "unresponsive").sandboxStatus()

        assertContains(status, "stopped")
        assertContains(status, "1024 MB of memory, 1 CPU(s) and a 4096 MB disk")
        assertContains(status, "files are deleted on 2026-10-13")
        assertContains(status, "filled the process limit")
        assertContains(status, "Snapshots (2 of 5)")
        assertContains(status, "$OLDER (\"before the upgrade\", taken 2026-09-13 12:10 UTC)")
        assertContains(status, "$NEWER (taken 2026-09-14 08:30 UTC)")
    }

    @Test
    fun `status for someone who never used the sandbox makes none`() = runBlocking {
        assertContains(tools(exists = false).sandboxStatus(), "no sandbox yet")
        assertFalse(requests.any { it.startsWith("POST") })
    }

    @Test
    fun `a snapshot is taken with its note on one line`() = runBlocking {
        val result = tools().takeSandboxSnapshot("  after the tests\n pass ")

        assertEquals("""{"note":"after the tests pass"}""", sentSnapshotBody)
        assertContains(result, "Snapshot $NEWER taken")
        assertContains(result, "\"after the tests pass\"")
    }

    // taking one stops the session, which would end a build halfway; the model decides what to do with it
    @Test
    fun `a snapshot is refused while a command runs`() = runBlocking {
        val refusal = toolFailure { tools(execs = """{"execs":[$RUNNING_EXEC]}""").takeSandboxSnapshot("midway") }

        assertContains(refusal, JOB)
        assertContains(refusal, "cancel it first")
        assertFalse(requests.any { it.endsWith("/snapshots") && it.startsWith("POST") })
    }

    @Test
    fun `restoring and deleting name the snapshot, and an unknown id reaches no request`() = runBlocking {
        val restored = tools().restoreSandboxSnapshot(OLDER)
        assertContains(restored, "\"before the upgrade\"")
        assertContains(restored, "everything written since then is gone")
        assertTrue("POST /v1/sandboxes/$SANDBOX_ID/snapshots/$OLDER/restore" in requests)

        assertContains(tools().deleteSandboxSnapshot(NEWER), "Deleted snapshot $NEWER")
        assertTrue("DELETE /v1/sandboxes/$SANDBOX_ID/snapshots/$NEWER" in requests)

        requests.clear()
        assertContains(toolFailure { tools().restoreSandboxSnapshot("../../stop") }, "`sandboxStatus` lists")
        assertContains(toolFailure { tools().deleteSandboxSnapshot("build-1") }, "no snapshot `build-1`")
        assertFalse(requests.any { it.endsWith("/restore") || it.startsWith("DELETE") })
    }

    @Test
    fun `a restart stops a running sandbox and leaves a stopped one alone`() = runBlocking {
        assertContains(tools(state = "running").restartSandbox(), "every file stayed")
        assertTrue("POST /v1/sandboxes/$SANDBOX_ID/stop" in requests)

        requests.clear()
        assertContains(tools().restartSandbox(), "Nothing was running")
        assertFalse(requests.any { it.endsWith("/stop") })
    }
}
