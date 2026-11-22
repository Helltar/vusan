package com.helltar.vusan.tools.exa

import com.helltar.vusan.infra.Http
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESULTS =
    """{"results":[{"url":"https://example.test/tides","title":"Tide tables","publishedDate":"2026-11-20T00:00:00.000Z",""" +
            """"highlights":["## High water\n\nat noon.","Low water at six."]}]}"""

class ExaToolsTest {

    private val sent = mutableListOf<Pair<String, JsonObject>>()

    private val lastBody: JsonObject get() = sent.last().second

    private fun tools(body: String = RESULTS): ExaTools {
        val http =
            Http.createClient(
                MockEngine { request ->
                    assertEquals("exa-test", request.headers["x-api-key"])
                    sent += request.url.encodedPath to Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )

        return ExaTools(ExaClient(http, "exa-test"))
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
}
