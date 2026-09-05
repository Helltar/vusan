package com.helltar.vusan.tools.searxng

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.images.ImageDownloadClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearxngToolsTest {

    private companion object {
        const val BASE_URL = "http://searxng:8080"
        const val IMAGE_HOST = "93.184.216.34"
    }

    private fun png(width: Int = 8, height: Int = 8): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()

    /** Captures the query parameters of every `/search` call the tools make. */
    private class SearchProbe {
        val calls = mutableListOf<Parameters>()
        val last: Parameters get() = calls.last()
    }

    private fun tools(
        body: String,
        outbox: BotOutbox = BotOutbox(),
        probe: SearchProbe = SearchProbe(),
        image: ByteArray? = null
    ): SearxngTools {
        val http =
            Http.createClient(
                MockEngine { request ->
                    if (request.url.host == IMAGE_HOST) {
                        return@MockEngine image
                            ?.let { respond(it, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) }
                            ?: respond("nope", HttpStatusCode.NotFound)
                    }

                    probe.calls += request.url.parameters
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
            )

        return SearxngTools(SearxngClient(http, BASE_URL), ImageDownloadClient(FileDownloadClient(http)), outbox)
    }

    private fun resultsJson(vararg urls: String) =
        """{"results":[${urls.joinToString(",") { """{"url":"$it","title":"T","content":"C","engine":"e"}""" }}]}"""

    private fun imagesJson(count: Int) =
        """{"results":[${
            (1..count).joinToString(",") {
                """{"url":"https://page/$it","title":"T$it","content":"","engine":"bing images","img_src":"https://$IMAGE_HOST/$it.png"}"""
            }
        }]}"""

    @Test
    fun `blank language and time range are left out of the request`() = runBlocking {
        // SearXNG answers an empty `language` with 400 Empty language parameter, and an unknown
        // `time_range` with a 400 too, so neither may ever reach the wire as an empty string.
        val probe = SearchProbe()
        tools(resultsJson("https://a"), probe = probe).metaSearch("kotlin", timeRange = "", language = "")

        assertNull(probe.last["language"])
        assertNull(probe.last["time_range"])
        assertEquals("kotlin", probe.last["q"])
        assertEquals("json", probe.last["format"])
    }

    @Test
    fun `an unsupported time range is dropped rather than sent`() = runBlocking {
        val probe = SearchProbe()
        tools(resultsJson("https://a"), probe = probe).metaSearch("kotlin", timeRange = "decade")

        assertNull(probe.last["time_range"])
    }

    @Test
    fun `supported scoping parameters are passed through`() = runBlocking {
        val probe = SearchProbe()
        tools(resultsJson("https://a"), probe = probe)
            .metaSearch("explosions", categories = "News", timeRange = "Week", language = "en-US")

        assertEquals("news", probe.last["categories"])
        assertEquals("week", probe.last["time_range"])
        assertEquals("en-US", probe.last["language"])
    }

    @Test
    fun `an unknown category falls back to no category filter`() = runBlocking {
        val probe = SearchProbe()
        tools(resultsJson("https://a"), probe = probe).metaSearch("kotlin", categories = "nonsense")

        assertNull(probe.last["categories"])
    }

    @Test
    fun `results are numbered with their urls and snippets`() = runBlocking {
        val body =
            """{"results":[{"url":"https://a","title":"Alpha","content":"first snippet","engine":"e",
               "publishedDate":"2026-07-01"},{"url":"https://b","title":"Beta","content":"second","engine":"e"}]}"""

        val result = tools(body).metaSearch("kotlin")

        assertContains(result, "Alpha")
        assertContains(result, "https://a")
        assertContains(result, "first snippet")
        assertContains(result, "2026-07-01")
        assertContains(result, "2. Beta")
    }

    @Test
    fun `maxResults caps how many results are reported`() = runBlocking {
        val result = tools(resultsJson("https://a", "https://b", "https://c")).metaSearch("kotlin", maxResults = 2)

        assertContains(result, "2. T")
        assertTrue("3. T" !in result)
    }

    @Test
    fun `a direct answer is reported above the results`() = runBlocking {
        val body = """{"results":[{"url":"https://a","title":"T","content":"C","engine":"e"}],"answers":[{"answer":"42"}]}"""
        val result = tools(body).metaSearch("meaning of life")

        assertContains(result, "Direct answer: 42")
        assertTrue(result.indexOf("Direct answer") < result.indexOf("https://a"))
    }

    @Test
    fun `an empty result set reports no results`() = runBlocking {
        val result = tools("""{"results":[]}""").metaSearch("kotlin")

        assertContains(result, "No results found")
    }

    @Test
    fun `a total engine outage is not reported as an empty topic`() = runBlocking {
        // every engine rate-limiting itself at once produces the same empty `results` as a genuine
        // miss, and calling that "no results" makes the model tell the user the topic has no coverage.
        val body =
            """{"results":[],"unresponsive_engines":[["brave","Suspended: too many requests"],
               ["duckduckgo","Suspended: CAPTCHA"]]}"""

        val result = tools(body).metaSearch("kotlin")

        assertTrue("No results found" !in result)
        assertContains(result, "rate-limited")
        assertContains(result, "webSearch")
    }

    @Test
    fun `an answer alone is still reported when no links come back`() = runBlocking {
        val body = """{"results":[],"answers":[{"answer":"42"}],"unresponsive_engines":[["brave","Suspended"]]}"""
        val result = tools(body).metaSearch("meaning of life")

        assertContains(result, "Direct answer: 42")
    }

    @Test
    fun `image search pins the engine list and sends no category`() = runBlocking {
        // `engines` combined with `categories=images` is ignored by SearXNG, which re-adds the
        // category's own stock-photo and icon engines on top and floods the results with noise.
        val probe = SearchProbe()
        tools(imagesJson(1), probe = probe, image = png()).metaSearchImages("red panda")

        assertNull(probe.last["categories"])
        assertContains(probe.last["engines"] ?: "", "bing images")
    }

    @Test
    fun `image search queues a media group and reports the count`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(imagesJson(3), outbox = outbox, image = png()).metaSearchImages("red panda", maxResults = 3)

        val group = assertIs<BotOutput.PhotoGroup>(outbox.pending.single().output)
        assertEquals(3, group.photos.size)
        assertContains(result, "Sent 3 images")
    }

    @Test
    fun `a single image is queued on its own rather than as a group`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(imagesJson(1), outbox = outbox, image = png()).metaSearchImages("red panda")

        assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertContains(result, "Sent 1 image")
    }

    @Test
    fun `image search reports nothing sent when no candidate downloads`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(imagesJson(2), outbox = outbox, image = null).metaSearchImages("red panda")

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "failed to download")
    }

    @Test
    fun `image search reports no images when the provider returns none`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools("""{"results":[]}""", outbox = outbox).metaSearchImages("red panda")

        assertTrue(outbox.pending.isEmpty())
        assertContains(result, "No images found")
    }

    @Test
    fun `an unreachable instance is reported without retry advice`() = runBlocking {
        val http = Http.createClient(MockEngine { throw java.net.ConnectException("Connection refused") })
        val tools = SearxngTools(SearxngClient(http, BASE_URL), ImageDownloadClient(FileDownloadClient(http)), BotOutbox())

        assertContains(tools.metaSearch("kotlin"), "temporarily unavailable")
    }
}
