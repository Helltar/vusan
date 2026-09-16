package com.helltar.vusan.tools.page

import com.helltar.vusan.infra.Http
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// a literal address keeps the private-address guard on its real code path without a DNS lookup
private const val PAGE_URL = "http://93.184.216.34/article"

class PageToolsTest {

    private fun tools(handler: MockRequestHandler) =
        PageTools(PageReader(FileDownloadClient(Http.createClient(MockEngine(handler)))))

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

    @Test
    fun `the charset declared by the server is honoured`() = runBlocking {
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
}
