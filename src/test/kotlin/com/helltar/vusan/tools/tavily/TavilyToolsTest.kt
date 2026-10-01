package com.helltar.vusan.tools.tavily

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.images.ImageDownloadClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

private const val RESULTS = """{"results":[{"title":"Tide tables","url":"https://example.test/tides","content":"High water at noon."}]}"""

class TavilyToolsTest {

    private val sent = mutableListOf<String>()

    private fun tools(): TavilyTools {
        val http = Http.createClient(MockEngine { request ->
            sent += (request.body as? TextContent)?.text.orEmpty()
            respond(RESULTS, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })

        return TavilyTools(TavilyClient(http, "tvly-test"), ImageDownloadClient(FileDownloadClient(http)), BotOutbox())
    }

    // the shared json leaves defaults out, so the count has none: a defaulted one would stay unsent and tavily would pick ten
    @Test
    fun `a search sends its result count and no search depth`() = runBlocking {
        tools().webSearch("tides")

        assertContains(sent.single(), """"max_results":5""")
        assertFalse("search_depth" in sent.single(), "a depth tavily was never sent is not declared either")
    }
}
