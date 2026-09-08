package com.helltar.vusan.tools.sites

import com.helltar.vusan.infra.Http
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.personKeyOrNull
import com.helltar.vusan.tools.toolFailure
import com.helltar.vusan.tools.workspace.WorkspaceClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val LIMITS = """{"files":1000,"fileBytes":26214400,"totalBytes":104857600,"pathDepth":10}"""
private const val UPLOAD = "3f0b2e1c4d5a6b7c8d9e0f1a2b3c4d5e"

class SiteToolsTest {
    private val context = RequestContext(chatId = 55L, userId = 55L, messageId = 1L, chatIsPrivate = true)
    private val uploaded = mutableListOf<Pair<String, ByteArray>>()
    private var commits = 0
    private var discards = 0

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun tools(
        archive: ByteArray? = null,
        site: String = """{"published":false}""",
        commitStatus: HttpStatusCode = HttpStatusCode.OK
    ): SiteTools {
        val siteEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/uploads" && request.method == HttpMethod.Post -> {
                    assertEquals("u55", request.url.parameters["owner"])
                    json("""{"uploadId":"$UPLOAD","expiresInMinutes":15,"limits":$LIMITS}""")
                }
                path == "/uploads/$UPLOAD" && request.method == HttpMethod.Put -> {
                    uploaded += request.url.parameters["path"].orEmpty() to request.body.toByteArray()
                    json("""{"path":"x","bytes":1}""")
                }
                path == "/uploads/$UPLOAD/commit" -> {
                    commits++
                    if (commitStatus.isSuccess()) {
                        json("""{"owner":"u55","label":"55","url":"https://55.example.com/","files":2,"bytes":9}""")
                    } else {
                        respond("""{"error":"Too many publishes in the last hour"}""", commitStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                path == "/uploads/$UPLOAD" && request.method == HttpMethod.Delete -> {
                    discards++
                    json("""{"uploadId":"$UPLOAD","discarded":true}""")
                }
                path == "/site" && request.method == HttpMethod.Delete -> json("""{"owner":"u55","removed":true}""")
                path == "/site" -> json(site)
                else -> error("Unexpected site request: $path")
            }
        }
        val workspaceEngine = MockEngine { request ->
            assertEquals("/files", request.url.encodedPath)
            archive?.let { respond(it, HttpStatusCode.OK) }
                ?: respond("""{"error":"No such path"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return SiteTools(
            SiteClient(Http.createClient(siteEngine), "http://sites:8090", "synthetic-site-secret-1234567890abc"),
            WorkspaceClient(Http.createClient(workspaceEngine), "http://workspace:8080", 600.seconds, "synthetic-workspace-secret-123456"),
            requireNotNull(context.personKeyOrNull)
        )
    }

    @Test
    fun `publishing uploads every file and hands back the link`() = runBlocking {
        val result = tools(zip("index.html" to "<h1>hi</h1>", "assets/app.js" to "run()"))
            .publishSite("site.zip")

        assertEquals(listOf("index.html", "assets/app.js"), uploaded.map { it.first })
        assertEquals("<h1>hi</h1>", uploaded.first().second.decodeToString())
        assertEquals(1, commits)
        assertEquals(0, discards)
        assertContains(result, "https://55.example.com/")
        assertContains(result, "Give the user that link")
    }

    @Test
    fun `an archive of the wrong shape publishes but says the link will show nothing`() = runBlocking {
        val result = tools(zip("site/index.html" to "page")).publishSite("site.zip")
        assertContains(result, "no `index.html` at the top")
    }

    @Test
    fun `a rejected archive is not left staged on the service`() = runBlocking {
        toolFailure { tools(zip("../escape.html" to "x")).publishSite("site.zip") }
        assertTrue(uploaded.isEmpty())
        assertEquals(0, commits)
        assertEquals(1, discards)
    }

    @Test
    fun `a refused commit is reported and the staged upload is dropped`() = runBlocking {
        val failure = toolFailure {
            tools(zip("index.html" to "hi"), commitStatus = HttpStatusCode.TooManyRequests).publishSite("site.zip")
        }
        assertContains(failure, "Too many publishes")
        assertEquals(1, discards)
    }

    @Test
    fun `a missing archive fails before anything is staged`() = runBlocking {
        toolFailure { tools(archive = null).publishSite("site.zip") }
        assertEquals(0, commits)
    }

    @Test
    fun `status reads the service rather than guessing`() = runBlocking {
        assertContains(tools().siteStatus(), "Nothing is published")
        val published = tools(site = """{"published":true,"url":"https://55.example.com/","files":3,"bytes":2048,"updatedAt":${System.currentTimeMillis()}}""")
        val status = published.siteStatus()
        assertContains(status, "https://55.example.com/")
        assertContains(status, "3 file(s)")
    }

    @Test
    fun `taking a site down says the workspace files are kept`() = runBlocking {
        assertContains(tools().unpublishSite(), "workspace files were kept")
    }
}
