package com.helltar.vusan.tools.tavily

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.images.FoundImage
import com.helltar.vusan.tools.images.ImageDownloadClient
import com.helltar.vusan.tools.images.MAX_IMAGE_RESULTS
import com.helltar.vusan.tools.images.deliverImageResults
import com.helltar.vusan.tools.suspendToolGuard
import io.ktor.http.*

@Suppress("unused")
class TavilyTools(
    private val client: TavilyClient,
    private val imageDownloader: ImageDownloadClient,
    private val outbox: BotOutbox
) : ToolSet {

    private companion object {
        const val MAX_SNIPPET_CHARS = 300
        const val MAX_SEARCH_OUTPUT_CHARS = 3_000
        const val MAX_EXTRACT_CHARS = 6_000
        val allowedTopics = setOf("general", "news", "finance")
        val allowedTimeRanges = setOf("day", "week", "month", "year")

        // the Instagram source pages expose images only through crawler/SEO endpoints
        // (lookaside.instagram.com, lookaside.fbsbx.com) that serve HTML, not the
        // actual file, so every download attempt fails. exclude these sources from
        // image search so the provider returns directly downloadable candidates.
        val imageExcludedDomains = listOf("instagram.com", "lookaside.instagram.com", "lookaside.fbsbx.com")
    }

    @Tool
    @LLMDescription(TavilyToolDescriptions.WEB_SEARCH)
    suspend fun webSearch(
        @LLMDescription(TavilyToolDescriptions.WEB_SEARCH_QUERY)
        query: String,
        @LLMDescription(TavilyToolDescriptions.WEB_SEARCH_MAX_RESULTS)
        maxResults: Int = 5,
        @LLMDescription(TavilyToolDescriptions.WEB_SEARCH_TOPIC)
        topic: String = "general",
        @LLMDescription(TavilyToolDescriptions.WEB_SEARCH_TIME_RANGE)
        timeRange: String = ""
    ): String = suspendToolGuard {
        val response =
            client.search(
                query = query,
                maxResults = maxResults,
                topic = topic.takeIf { it in allowedTopics },
                timeRange = timeRange.takeIf { it in allowedTimeRanges }
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

    @Tool
    @LLMDescription(TavilyToolDescriptions.SEARCH_IMAGES)
    suspend fun searchImages(
        @LLMDescription(TavilyToolDescriptions.SEARCH_IMAGES_QUERY)
        query: String,
        @LLMDescription(TavilyToolDescriptions.SEARCH_IMAGES_MAX_RESULTS)
        maxResults: Int = 5
    ): String = suspendToolGuard {
        val capped = maxResults.coerceIn(1, MAX_IMAGE_RESULTS)

        val response =
            client.search(
                query = query,
                maxResults = capped,
                includeImages = true,
                excludeDomains = imageExcludedDomains
            )

        // `exclude_domains` filters Tavily's source pages, not the image CDN host, so a lookaside
        // image URL can still arrive from another source page. drop them here before they consume a slot.
        val candidates =
            response.images
                .filterNot { isExcludedImageHost(it.url) }
                .map { FoundImage(url = it.url, description = it.description) }

        imageDownloader.deliverImageResults(
            query = query,
            candidates = candidates,
            limit = capped,
            outbox = outbox
        )
    }

    @Tool
    @LLMDescription(TavilyToolDescriptions.EXTRACT_PAGE_CONTENT)
    suspend fun extractPageContent(
        @LLMDescription(TavilyToolDescriptions.EXTRACT_PAGE_URL)
        url: String
    ): String = suspendToolGuard {
        val response = client.extract(url)
        val result = response.results.firstOrNull()

        if (result == null) {
            val reason = response.failedResults.firstOrNull()?.error ?: "unknown error"
            return@suspendToolGuard "Could not extract content from $url: $reason"
        }

        val content = result.rawContent.trim().limitTo(MAX_EXTRACT_CHARS)

        if (content.isBlank()) {
            return@suspendToolGuard "Page at $url returned empty content."
        }

        buildString {
            append("Content from ")
            append(url)
            appendLine(":")
            append(content)

            if (result.rawContent.length > MAX_EXTRACT_CHARS) {
                appendLine()
                append("[content truncated at $MAX_EXTRACT_CHARS chars]")
            }
        }
    }

    private fun isExcludedImageHost(url: String): Boolean {
        val host = runCatching { Url(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return imageExcludedDomains.any { host == it || host.endsWith(".$it") }
    }
}
