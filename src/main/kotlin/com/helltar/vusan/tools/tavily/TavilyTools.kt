package com.helltar.vusan.tools.tavily

import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.images.FoundImage
import com.helltar.vusan.tools.images.ImageDownloadClient
import com.helltar.vusan.tools.images.MAX_IMAGE_RESULTS
import com.helltar.vusan.tools.images.deliverImageResults
import com.helltar.vusan.tools.images.photosRefusedReply
import com.helltar.vusan.tools.suspendToolGuard
import io.ktor.http.*

class TavilyTools(
    private val client: TavilyClient,
    private val imageDownloader: ImageDownloadClient,
    private val outbox: BotOutbox,
) : ToolSet {

    @Tool(TavilyToolDescriptions.WEB_SEARCH, readOnly = true, copiedToSandbox = true)
    suspend fun webSearch(
        @Arg(TavilyToolDescriptions.WEB_SEARCH_QUERY)
        query: String,
        @Arg(TavilyToolDescriptions.WEB_SEARCH_MAX_RESULTS)
        maxResults: Int = 5,
        @Arg(TavilyToolDescriptions.WEB_SEARCH_TOPIC)
        topic: String = "general",
        @Arg(TavilyToolDescriptions.WEB_SEARCH_TIME_RANGE)
        timeRange: String = "",
    ): String = suspendToolGuard {
        val response =
            client.search(
                query = query,
                maxResults = maxResults,
                topic = topic.takeIf { it in allowedTopics },
                timeRange = timeRange.takeIf { it in allowedTimeRanges },
            )

        if (response.results.isEmpty()) {
            return@suspendToolGuard """No results found for "$query"."""
        }

        buildString {
            appendLine("""Web search results for "$query":""")

            response.results.forEachIndexed { i, result ->
                append(i + 1)
                append(". ")
                appendLine(result.title)
                append("   URL: ")
                appendLine(result.url)

                result.publishedDate?.let {
                    append("   Published: ")
                    appendLine(it)
                }

                val snippet = result.content.trimIndent().limitTo(MAX_SNIPPET_CHARS)

                if (snippet.isNotBlank()) {
                    append("   ")
                    appendLine(snippet)
                }
            }
        }.trim().limitTo(MAX_SEARCH_OUTPUT_CHARS)
    }

    @Tool(TavilyToolDescriptions.SEARCH_IMAGES)
    suspend fun searchImages(
        @Arg(TavilyToolDescriptions.SEARCH_IMAGES_QUERY)
        query: String,
        @Arg(TavilyToolDescriptions.SEARCH_IMAGES_MAX_RESULTS)
        maxResults: Int = 5,
        @Arg(TavilyToolDescriptions.SEARCH_IMAGES_SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        if (send) outbox.photosRefusedReply()?.let { return@suspendToolGuard it }

        val capped = maxResults.coerceIn(1, MAX_IMAGE_RESULTS)

        val response =
            client.search(
                query = query,
                maxResults = capped,
                includeImages = true,
                excludeDomains = imageExcludedDomains,
            )

        // `exclude_domains` filters source pages, not image CDN hosts, so a lookaside image URL can still
        // arrive via another page; drop it before it takes a slot.
        val candidates =
            response.images
                .filterNot { isExcludedImageHost(it.url) }
                .map { FoundImage(url = it.url, description = it.description) }

        imageDownloader.deliverImageResults(
            query = query,
            candidates = candidates,
            limit = capped,
            outbox = outbox,
            send = send,
        )
    }

    @Tool(TavilyToolDescriptions.EXTRACT_PAGE_CONTENT, readOnly = true, copiedToSandbox = true)
    suspend fun extractPageContent(
        @Arg(TavilyToolDescriptions.EXTRACT_PAGE_URL)
        url: String,
    ): String = suspendToolGuard {
        val response = client.extract(url)
        val result = response.results.firstOrNull()

        if (result == null) {
            val reason = response.failedResults.firstOrNull()?.error ?: "unknown error"
            error("Could not extract content from $url: $reason. `readPage` reads the same address directly.")
        }

        val content = result.rawContent.trim().limitTo(MAX_EXTRACT_CHARS)

        if (content.isBlank()) {
            error("Page at $url returned empty content. `readPage` reads the same address directly.")
        }

        // shaped as `readPage` answers: the page is evidence inside its own block, never bare text
        buildString {
            appendLine("Use this page as evidence for the answer.")
            append(xmlBlock("page", "url: $url\n\n$content"))

            if (result.rawContent.length > MAX_EXTRACT_CHARS) {
                appendLine()
                append("The page goes on past $MAX_EXTRACT_CHARS characters. `readPage` reads a long page in parts.")
            }
        }
    }

    private fun isExcludedImageHost(url: String): Boolean {
        val host = runCatching { Url(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false

        return imageExcludedDomains.any { host == it || host.endsWith(".$it") }
    }

    private companion object {
        const val MAX_SNIPPET_CHARS = 300
        const val MAX_SEARCH_OUTPUT_CHARS = 3_000
        // a long article runs past a few thousand characters and this tool cannot page, so the cut must leave
        // a real page's body readable; readPage continues by offset.
        const val MAX_EXTRACT_CHARS = 16_000
        val allowedTopics = setOf("general", "news", "finance")
        val allowedTimeRanges = setOf("day", "week", "month", "year")

        // instagram pages expose images only through crawler endpoints (lookaside.instagram.com,
        // lookaside.fbsbx.com) that serve HTML, not the file, so every download fails; excluding them makes
        // the provider return downloadable candidates.
        val imageExcludedDomains = listOf("instagram.com", "lookaside.instagram.com", "lookaside.fbsbx.com")
    }
}
