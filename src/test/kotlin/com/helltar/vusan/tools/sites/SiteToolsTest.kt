package com.helltar.vusan.tools.sites

import com.helltar.vusan.infra.Http
import com.helltar.vusan.request.personKeyOrNull
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.tools.toolFailure
import com.helltar.vusan.tools.sandbox.SandboxClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val PUBLISHED =
    """{"site":"u55","url":"https://u55.example.test","release":"0f1e2d3c4b5a69788796a5b4c3d2e1f0","files":3,"bytes":2048,"publishedAt":"2026-09-13T12:00:00Z"}"""

class SiteToolsTest {
    private val context = requestContext(chatId = 55L, userId = 55L)
    private val requests = mutableListOf<String>()
    private var publishedPath: String? = null

    private fun tools(
        entries: List<String> = listOf("index.html", "assets"),
        site: String? = PUBLISHED,
        publish: Pair<HttpStatusCode, String> = HttpStatusCode.OK to PUBLISHED,
    ): SiteTools {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += "${request.method.value} $path"
            assertTrue(path.startsWith("/v1/sandboxes/u55"), "a request left this person's sandbox: $path")
            when {
                path.endsWith("/publish") -> {
                    publishedPath = Regex(""""path":"([^"]*)"""").find(request.body.toByteArray().decodeToString())?.groupValues?.get(1)
                    respond(publish.second, publish.first, headersOf(HttpHeaders.ContentType, "application/problem+json"))
                }

                path.endsWith("/files/entries") ->
                    respond(
                        """{"path":"x","entries":[${entries.joinToString(",") { """{"name":"$it","type":"file"}""" }}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )

                path.endsWith("/site") && request.method == HttpMethod.Delete ->
                    if (site == null) notFound() else respond("", HttpStatusCode.NoContent)

                path.endsWith("/site") ->
                    if (site == null) notFound() else respond(site, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

                else -> respond("""{"name":"u55"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

        return SiteTools(
            SandboxClient(Http.createClient(engine), "http://regolith:8080", "test-token"),
            requireNotNull(context.personKeyOrNull),
        )
    }

    @Test
    fun `publishing hands back the link and the size`() = runBlocking {
        val result = tools().publishSite("site")
        assertEquals("site", publishedPath)
        assertContains(result, "https://u55.example.test")
        assertContains(result, "3 file(s)")
        assertContains(result, "2 KB")
    }

    // a directory with no index.html publishes fine and its link then opens nothing, which the result
    // alone never shows
    @Test
    fun `a directory with no index page is published with a warning`() = runBlocking {
        val result = tools(entries = listOf("main.js", "style.css")).publishSite("dist")
        assertContains(result, "https://u55.example.test")
        assertContains(result, "no `index.html`")
    }

    @Test
    fun `a server that cannot publish says so in its own words`() = runBlocking {
        val refusal = """{"type":"urn:regolith:error:not_implemented","title":"Not implemented","status":501,"detail":"This server publishes nothing: no pages role is configured","code":"not_implemented"}"""
        val sandbox = tools(publish = HttpStatusCode.NotImplemented to refusal)
        assertContains(toolFailure { sandbox.publishSite("site") }, "publishes nothing")
    }

    @Test
    fun `status reads the site rather than the sandbox`() = runBlocking {
        assertContains(tools().siteStatus(), "Published at https://u55.example.test")
        assertContains(tools(site = null).siteStatus(), "Nothing is published")
    }

    @Test
    fun `taking a site down leaves the sandbox files alone`() = runBlocking {
        assertContains(tools().unpublishSite(), "sandbox files were kept")
        assertContains(tools(site = null).unpublishSite(), "nothing published")
        assertFalse(requests.any { it.startsWith("DELETE /v1/sandboxes/u55/files") })
    }
}

private fun MockRequestHandleScope.notFound() = respond(
    """{"type":"urn:regolith:error:not_found","title":"Not found","status":404,"detail":"Nothing is published","code":"not_found"}""",
    HttpStatusCode.NotFound,
    headersOf(HttpHeaders.ContentType, "application/problem+json"),
)
