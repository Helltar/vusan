package com.helltar.vusan.tools.exa

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.images.ImageDownloadClient
import com.helltar.vusan.tools.images.testPicture
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESULTS =
    """{"results":[{"url":"https://example.test/tides","title":"Tide tables","publishedDate":"2026-11-20T00:00:00.000Z",""" +
            """"highlights":["## High water\n\nat noon.","Low water at six."]}]}"""

class ExaToolsTest {

    private val sent = mutableListOf<Pair<String, JsonObject>>()

    private val lastBody: JsonObject get() = sent.last().second

    /** Answers exa's api with [body] and the image host with the picture named by the path, or a 404. */
    private fun tools(body: String = RESULTS, outbox: BotOutbox = BotOutbox(), images: Map<String, ByteArray> = emptyMap()): ExaTools {
        val http =
            Http.createClient(
                MockEngine { request ->
                    if (request.url.host == IMAGE_HOST) {
                        return@MockEngine images[request.url.encodedPath]
                            ?.let { respond(it, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) }
                            ?: respond("nope", HttpStatusCode.NotFound)
                    }

                    assertEquals("exa-test", request.headers["x-api-key"])
                    sent += request.url.encodedPath to Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )

        return ExaTools(ExaClient(http, "exa-test"), ImageDownloadClient(FileDownloadClient(http)), outbox)
    }

    // the shared json omits defaults, so the count has none: a defaulted one would stay unsent and exa would
    // pick ten
    @Test
    fun `a search asks for capped excerpts and its result count`() = runBlocking {
        tools().webSearch("tides")

        assertEquals("/search", sent.single().first)
        assertEquals("auto", lastBody.getValue("type").jsonPrimitive.content)
        assertEquals(5, lastBody.getValue("numResults").jsonPrimitive.int)
        assertTrue(lastBody.getValue("contents").jsonObject.getValue("highlights").jsonObject.getValue("maxCharacters").jsonPrimitive.int > 0)
        assertNull(lastBody["category"])
        assertNull(lastBody["startPublishedDate"])
    }

    @Test
    fun `a known category and time range are sent, an unknown one is dropped`() = runBlocking {
        tools().webSearch("tides", category = "News", timeRange = "week")

        assertEquals("news", lastBody.getValue("category").jsonPrimitive.content)
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(lastBody.getValue("startPublishedDate").jsonPrimitive.content))

        tools().webSearch("tides", category = "podcasts", timeRange = "decade")

        assertNull(lastBody["category"])
        assertNull(lastBody["startPublishedDate"])
    }

    // exa answers a date filter on company and people searches with a 400
    @Test
    fun `a people search goes out without its date filter`() = runBlocking {
        tools().webSearch("tide researchers", category = "people", timeRange = "year")

        assertEquals("people", lastBody.getValue("category").jsonPrimitive.content)
        assertNull(lastBody["startPublishedDate"])
    }

    @Test
    fun `results are numbered with their urls, dates and excerpts on one line`() = runBlocking {
        val result = tools().webSearch("tides")

        assertContains(result, "1. Tide tables")
        assertContains(result, "URL: https://example.test/tides")
        assertContains(result, "Published: 2026-11-20")
        assertContains(result, "## High water at noon. … Low water at six.")
    }

    @Test
    fun `an empty result set reports no results`() = runBlocking {
        assertContains(tools("""{"results":[]}""").webSearch("tides"), "No results found")
    }

    @Test
    fun `an image search asks for each page's images and no excerpts`() = runBlocking {
        tools("""{"results":[]}""").searchImages("red panda")

        val contents = lastBody.getValue("contents").jsonObject

        assertEquals("/search", sent.single().first)
        assertTrue(contents.getValue("extras").jsonObject.getValue("imageLinks").jsonPrimitive.int > 0)
        assertNull(contents["highlights"])
    }

    @Test
    fun `previews go first, and decorations, duplicates and small pictures are skipped`() = runBlocking {
        val body =
            """{"results":[""" +
                    """{"url":"https://a.test","image":"https://$IMAGE_HOST/preview-a.png","extras":{"imageLinks":[""" +
                    """"https://$IMAGE_HOST/preview-a.png?v=2","https://$IMAGE_HOST/site-logo.png","https://$IMAGE_HOST/cc.svg.png",""" +
                    """"https://$IMAGE_HOST/thumb.png","https://$IMAGE_HOST/in-page.png","data:image/png;base64,AAAA"]}},""" +
                    """{"url":"https://b.test","image":"https://$IMAGE_HOST/preview-b.png"}]}"""

        val images =
            mapOf(
                "/preview-a.png" to testPicture(800, 600, scene = 1),
                "/preview-b.png" to testPicture(640, 480, scene = 2),
                "/site-logo.png" to testPicture(400, 400, scene = 3),
                "/cc.svg.png" to testPicture(400, 400, scene = 4),
                "/thumb.png" to testPicture(120, 90, scene = 5),
                "/in-page.png" to testPicture(1024, 768, scene = 6),
            )

        val outbox = BotOutbox()
        val result = tools(body, outbox, images).searchImages("red panda", maxResults = 10)
        val group = assertIs<BotOutput.PhotoGroup>(outbox.pending.single().output)

        assertContains(result, "Sent 3 images")
        assertEquals(listOf(800, 640, 1024), group.photos.map { ImageIO.read(it.bytes.inputStream()).width })
    }

    @Test
    fun `an image search in a chat that refuses photos asks exa nothing`() = runBlocking {
        val result = tools(outbox = BotOutbox(ChatCapabilities(photos = false))).searchImages("red panda")

        assertContains(result, "does not accept photos")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a page is read inside its own block`() = runBlocking {
        val result = tools("""{"results":[{"url":"https://example.test/tides","text":"High water at noon."}],"statuses":[]}""")
            .extractPageContent("https://example.test/tides")

        assertEquals("/contents", sent.single().first)
        assertEquals("https://example.test/tides", lastBody.getValue("urls").toString().trim('[', ']', '"'))
        assertContains(result, "<page>")
        assertContains(result, "High water at noon.")
        assertFalse("goes on" in result)
    }

    @Test
    fun `a page longer than the cut says it goes on`() = runBlocking {
        val tools = tools("""{"results":[{"url":"https://example.test/long","text":"${"a".repeat(20_000)}"}]}""")
        val result = tools.extractPageContent("https://example.test/long")
        val asked = lastBody.getValue("text").jsonObject.getValue("maxCharacters").jsonPrimitive.int

        assertContains(result, "goes on past ${asked - 1} characters")
    }

    @Test
    fun `a page exa could not fetch fails with its reason and points to readPage`() = runBlocking {
        val body = """{"results":[],"statuses":[{"id":"https://example.test/gone","status":"error","error":{"tag":"CRAWL_NOT_FOUND","httpStatusCode":404}}]}"""
        val message = toolFailure { tools(body).extractPageContent("https://example.test/gone") }

        assertContains(message, "CRAWL_NOT_FOUND")
        assertContains(message, "`readPage`")
    }

    private companion object {
        const val IMAGE_HOST = "93.184.216.34"
    }
}
