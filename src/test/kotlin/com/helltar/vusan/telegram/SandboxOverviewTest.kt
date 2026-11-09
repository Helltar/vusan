package com.helltar.vusan.telegram

import com.helltar.vusan.i18n.EnglishMessages
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.infra.Http
import com.helltar.vusan.tools.sandbox.SANDBOX_ID
import com.helltar.vusan.tools.sandbox.SandboxClient
import com.helltar.vusan.tools.sandbox.problemDocument
import com.helltar.vusan.tools.sandbox.sandboxInfo
import com.helltar.vusan.tools.sandbox.sandboxPage
import com.helltar.vusan.tools.sandbox.serverInfo
import com.helltar.vusan.tools.sandbox.snapshotInfo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

private const val SNAPSHOT = "9a0c4e1b7d2f4a6c8e0b1d3f5a7c9e2b"

private const val SITE =
    """{"url":"https://k7m2q9xwtp.example.test","release":"0f1e2d3c4b5a69788796a5b4c3d2e1f0","files":3,"bytes":2048,"publishedAt":"2026-09-13T12:00:00Z","until":"2026-10-13T12:00:00Z","hasIndex":true}"""

private const val RUNNING_EXEC =
    """{"id":"9b7f0d2c4e6a418d93f5c0b1a2d3e4f5","status":"running","startedAt":"2026-09-13T12:00:00Z","outputEnd":0,"outputTruncated":false,"stdinOpen":false}"""

// the fake sandbox is deleted on 2026-10-13, ten days after this
private val NOW = Instant.parse("2026-10-03T12:00:00Z")

class SandboxOverviewTest {
    private val requests = mutableListOf<String>()

    private fun client(
        exists: Boolean = true,
        state: String = "stopped",
        homeReleasedAt: String? = null,
        site: String? = SITE,
        sitesRefused: Boolean = false,
        execs: String = """{"execs":[]}""",
        down: Boolean = false,
    ): SandboxClient {
        val info = sandboxInfo("telegram:55", state = state, homeReleasedAt = homeReleasedAt)

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += "${request.method.value} $path"

            when {
                down -> problem("unavailable", HttpStatusCode.ServiceUnavailable)
                path == "/v1/sandboxes" -> json(if (exists) sandboxPage(info) else sandboxPage())
                path == "/v1/sandboxes/$SANDBOX_ID" -> json(info)
                path == "/v1/info" -> json(serverInfo(maxSnapshots = 5))
                path.endsWith("/execs") -> json(execs)
                path.endsWith("/snapshots") -> json("""{"snapshots":[${snapshotInfo(SNAPSHOT, note = "before <the> upgrade")}]}""")
                path.endsWith("/site") && sitesRefused -> problem("not_implemented", HttpStatusCode.NotImplemented)
                path.endsWith("/site") -> site?.let { json(it) } ?: problem("not_found", HttpStatusCode.NotFound)
                else -> error("Unexpected request: ${request.method.value} $path")
            }
        }

        return SandboxClient(Http.createClient(engine), "http://regolith:8080", "test-token")
    }

    private fun reply(client: SandboxClient, messages: Messages = EnglishMessages): String =
        runBlocking { client.overviewReply(55L, messages, NOW) }

    @Test
    fun `a stopped sandbox shows when its files go, its snapshots and its site`() {
        val text = reply(client())

        assertEquals(
            """
            <b>Your sandbox</b>

            💤 Stopped, the next command starts it
            📦 Files are kept until 2026-10-13 (days left: 10). Every use moves that date
            📸 Snapshots: 1 of 5
              • 2026-09-13 · 50.0 MB · before &lt;the&gt; upgrade
            🌐 Site: https://k7m2q9xwtp.example.test
            ↳ Files: 3 · 2 KB · online until 2026-10-13 (days left: 10)
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `a running sandbox counts the commands still in progress`() {
        val text = reply(client(state = "running", execs = """{"execs":[$RUNNING_EXEC]}"""))

        assertContains(text, "🟢 Running · commands in progress: 1")
    }

    @Test
    fun `files taken by retention are reported instead of a date to count down to`() {
        val text = reply(client(homeReleasedAt = "2026-10-01T00:00:00Z"))

        assertContains(text, "The files were deleted on 2026-10-01")
        assertFalse("Files are kept until" in text)
        assertContains(text, "🌐 Site:")
    }

    @Test
    fun `no site is no line, and neither is a server that publishes nothing`() {
        assertFalse("🌐" in reply(client(site = null)))

        val text = reply(client(sitesRefused = true))
        assertFalse("🌐" in text)
        assertContains(text, "📸 Snapshots: 1 of 5")
    }

    @Test
    fun `asking does not create a sandbox for someone who never used one`() {
        assertEquals(EnglishMessages.sandboxNoneReply, reply(client(exists = false)))
        assertTrue(requests.none { it.startsWith("POST") }, "requests: $requests")
    }

    @Test
    fun `a server that cannot be asked is an apology, never its own words`() {
        assertEquals(EnglishMessages.sandboxUnavailableReply, reply(client(down = true)))
    }

    @Test
    fun `every language renders the whole answer`() {
        Language.entries.forEach { language ->
            val text = reply(client(state = "running", execs = """{"execs":[$RUNNING_EXEC]}"""), Messages.of(language))

            // the title, a blank line, five facts and the site's second line under its arrow
            assertEquals(8, text.lines().size, "$language: $text")
            assertEquals(1, text.lines().count { it.isBlank() }, "$language: $text")
            assertTrue(text.lines().last().startsWith("↳ "), "$language: $text")
        }
    }
}

private fun MockRequestHandleScope.json(body: String) =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

private fun MockRequestHandleScope.problem(code: String, status: HttpStatusCode) =
    respond(
        problemDocument(code, status.value, status.description, "the server said no"),
        status,
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )
