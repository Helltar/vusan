package com.helltar.vusan.tools.page

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.images.ImageDownloadClient
import com.helltar.vusan.tools.images.testPicture
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// a literal address keeps the private-address guard on its real path without DNS
private const val PAGE_URL = "http://93.184.216.34/article"

class PageToolsTest {

    private fun tools(outbox: BotOutbox = BotOutbox(), handler: MockRequestHandler): PageTools {
        val downloader = FileDownloadClient(Http.createClient(MockEngine(handler)))

        return PageTools(PageReader(downloader), ImageDownloadClient(downloader), outbox)
    }

    /** Serves [html] at the page and each picture in [images] by its path, a 404 for the rest. */
    private fun pictures(html: String, images: Map<String, ByteArray>, outbox: BotOutbox = BotOutbox()) =
        tools(outbox) { request ->
            val path = request.url.encodedPath

            when {
                path == "/article" -> respond(html, headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
                path in images -> respond(images.getValue(path), headers = headersOf(HttpHeaders.ContentType, "image/png"))
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }

    @Test
    fun `an article is read without its chrome and keeps its paragraphs`() = runBlocking {
        val html = """
            <html><head><title>Signal over noise</title><script>track()</script></head>
            <body>
              <nav><a href="/">Home</a> · <a href="/about">About</a></nav>
              <article>
                <h1>Signal over noise</h1>
                <p>First   paragraph
                   wrapped in the source.</p>
                <p>Second paragraph.<br>With a break.</p>
                <ul><li>one</li><li>two</li></ul>
              </article>
              <footer>© Example</footer>
            </body></html>
        """.trimIndent()

        val result = tools { respond(html, headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")) }.readPage(PAGE_URL)

        assertContains(result, "title: Signal over noise")
        assertContains(result, "First paragraph wrapped in the source.\n\nSecond paragraph.\nWith a break.")
        assertContains(result, "- one\n- two")
        assertFalse("Home" in result, "navigation leaked into the text")
        assertFalse("track()" in result, "a script leaked into the text")
        assertFalse("© Example" in result, "the footer leaked into the text")
    }

    // an instruction for a model planted where no reader sees it
    @Test
    fun `text the page hides from a reader is not read`() = runBlocking {
        val html = """
            <html><body><article>
              <p>Visible paragraph.</p>
              <p hidden>Hidden by attribute.</p>
              <p aria-hidden="true">Hidden from assistive technology.</p>
              <div style="display: none">Hidden by display.</div>
              <span style="visibility:hidden">Hidden by visibility.</span>
            </article></body></html>
        """.trimIndent()

        val result = tools { respond(html, headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")) }.readPage(PAGE_URL)

        assertContains(result, "Visible paragraph.")
        assertFalse("Hidden" in result, result)
    }

    @Test
    fun `the charset declared by the server is honored`() = runBlocking {
        val html = "<html><body><p>Привіт, світе</p></body></html>".toByteArray(charset("windows-1251"))

        val result = tools { respond(html, headers = headersOf(HttpHeaders.ContentType, "text/html; charset=windows-1251")) }.readPage(PAGE_URL)

        assertContains(result, "Привіт, світе")
    }

    @Test
    fun `plain text is passed through`() = runBlocking {
        val result = tools { respond("line one\n\n\n\nline two", headers = headersOf(HttpHeaders.ContentType, "text/plain")) }.readPage(PAGE_URL)

        assertContains(result, "line one\n\nline two")
    }

    @Test
    fun `a binary is refused with the way to send it instead`() = runBlocking {
        val message =
            toolFailure {
                tools { respond(ByteArray(64), headers = headersOf(HttpHeaders.ContentType, "application/pdf")) }.readPage(PAGE_URL)
            }

        assertContains(message, "application/pdf")
        assertContains(message, "downloadFile")
    }

    @Test
    fun `a long page is read in parts that continue from the stated offset`() = runBlocking {
        val body = "<p>" + "word ".repeat(10_000) + "</p>"
        val tools = tools { respond(body, headers = headersOf(HttpHeaders.ContentType, "text/html")) }

        val first = tools.readPage(PAGE_URL)

        assertTrue(first.length < body.length)
        assertContains(first, "characters: 0 to $MAX_PAGE_TEXT_CHARS of 49999")
        assertContains(first, "Continue reading with offset=$MAX_PAGE_TEXT_CHARS.")

        val last = tools.readPage(PAGE_URL, offset = 3 * MAX_PAGE_TEXT_CHARS)

        assertContains(last, "characters: ${3 * MAX_PAGE_TEXT_CHARS} to 49999 of 49999")
        assertFalse("Continue reading" in last, "the last part still offered a continuation")

        assertContains(toolFailure { tools.readPage(PAGE_URL, offset = 49999) }, "past the end")
    }

    @Test
    fun `a page with nothing to read says so instead of returning an empty block`() = runBlocking {
        val message =
            toolFailure {
                tools { respond("<html><body><script>render()</script></body></html>", headers = headersOf(HttpHeaders.ContentType, "text/html")) }.readPage(PAGE_URL)
            }

        assertContains(message, "no readable text")
    }

    @Test
    fun `a page's preview goes first, then its article's photos, without chrome, icons or thumbnails`() = runBlocking {
        val html = """
            <html><head><title>Red pandas</title><meta property="og:image" content="/preview.png"></head>
            <body>
              <header><img src="/masthead.png"></header>
              <article>
                <img src="/site-logo.png">
                <figure><img src="/placeholder.png" data-src="/lazy.png"></figure>
                <img srcset="/small.png 480w, /large.png 1200w">
                <img src="/thumb.png">
              </article>
              <aside><img src="/other-story.png"></aside>
            </body></html>
        """.trimIndent()

        val images =
            listOf("/preview.png", "/masthead.png", "/site-logo.png", "/placeholder.png", "/lazy.png", "/small.png", "/large.png", "/other-story.png")
                .withIndex()
                .associate { (scene, path) -> path to testPicture(800, 600, scene) } + ("/thumb.png" to testPicture(120, 90, scene = 9))

        val outbox = BotOutbox()
        val result = pictures(html, images, outbox).pageImages(PAGE_URL, maxResults = 10)
        val group = assertIs<BotOutput.PhotoGroup>(outbox.pending.single().output)

        assertContains(result, "Sent 3 images")
        assertEquals(3, group.photos.size)
    }

    @Test
    fun `a link straight to an image is its own picture`() = runBlocking {
        val outbox = BotOutbox()
        val picture = testPicture(800, 600)

        val result = tools(outbox) { respond(picture, headers = headersOf(HttpHeaders.ContentType, "image/png")) }.pageImages(PAGE_URL)

        assertContains(result, "Sent 1 image")
        assertIs<BotOutput.Photo>(outbox.pending.single().output)
    }

    @Test
    fun `one photo linked at several sizes goes out once`() = runBlocking {
        val html = """
            <html><head><meta property="og:image" content="/hero-1200.png"></head>
            <body><article><img src="/hero-600.png"><img src="/second.png"></article></body></html>
        """.trimIndent()

        val images =
            mapOf(
                "/hero-1200.png" to testPicture(1200, 800, scene = 1),
                "/hero-600.png" to testPicture(600, 400, scene = 1),
                "/second.png" to testPicture(800, 600, scene = 2),
            )

        val outbox = BotOutbox()

        assertContains(pictures(html, images, outbox).pageImages(PAGE_URL), "Sent 2 images")
        assertEquals(1200, ImageIO.read(assertIs<BotOutput.PhotoGroup>(outbox.pending.single().output).photos.first().bytes.inputStream()).width)
    }

    @Test
    fun `a page without pictures says so and sends nothing`() = runBlocking {
        val outbox = BotOutbox()
        val result = pictures("<html><body><article><p>Words only.</p></article></body></html>", emptyMap(), outbox).pageImages(PAGE_URL)

        assertContains(result, "No pictures found")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `a chat that refuses photos fetches nothing`() = runBlocking {
        var requests = 0
        val result = tools(BotOutbox(ChatCapabilities(photos = false))) { requests++; respond("") }.pageImages(PAGE_URL)

        assertContains(result, "does not accept photos")
        assertEquals(0, requests)
    }

    @Test
    fun `the widest srcset candidate wins, commas inside an address included`() {
        assertEquals("b.jpg", "a.jpg 480w, b.jpg 960w".largestInSrcset())
        assertEquals("b.jpg", "a.jpg, b.jpg 2x".largestInSrcset())
        assertEquals("/w_800,h_600/a.jpg", "/w_400,h_300/a.jpg 400w, /w_800,h_600/a.jpg 800w".largestInSrcset())
        assertEquals(null, "  ".largestInSrcset())
    }
}
