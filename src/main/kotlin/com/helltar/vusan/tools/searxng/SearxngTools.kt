package com.helltar.vusan.tools.searxng

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
import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress("unused")
class SearxngTools(
    private val client: SearxngClient,
    private val imageDownloader: ImageDownloadClient,
    private val outbox: BotOutbox
) : ToolSet {

    private companion object {
        const val MAX_SNIPPET_CHARS = 400
        const val MAX_SEARCH_OUTPUT_CHARS = 4_000
        const val MAX_ANSWER_CHARS = 600
        const val MAX_RESULTS_LIMIT = 15

        val allowedCategories =
            setOf("general", "news", "it", "science", "videos", "music", "files", "social media", "map")

        // an unknown time_range is a 400, unlike an unknown category, so this set is a hard guard
        val allowedTimeRanges = setOf("day", "week", "month", "year")

        // SearXNG mixes stock-photo and icon engines into every image query — unsplash, pexels, artic,
        // devicons and lucide answer anything with something, so `blackpink` comes back with an 1878
        // self-portrait. pinning the engine list is the only filter that works: `engines` combined with
        // `categories=images` is ignored, because the category adds its own engines back on top.
        const val IMAGE_ENGINES = "duckduckgo images,bing images,google cse images,wikicommons.images,flickr"

        val log = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(SearxngToolDescriptions.META_SEARCH)
    suspend fun metaSearch(
        @LLMDescription(SearxngToolDescriptions.META_SEARCH_QUERY)
        query: String,
        @LLMDescription(SearxngToolDescriptions.META_SEARCH_MAX_RESULTS)
        maxResults: Int = 6,
        @LLMDescription(SearxngToolDescriptions.META_SEARCH_CATEGORIES)
        categories: String = "general",
        @LLMDescription(SearxngToolDescriptions.META_SEARCH_TIME_RANGE)
        timeRange: String = "",
        @LLMDescription(SearxngToolDescriptions.META_SEARCH_LANGUAGE)
        language: String = ""
    ): String = suspendToolGuard {
        val response =
            client.search(
                query = query,
                categories = categories.trim().lowercase().takeIf { it in allowedCategories },
                timeRange = timeRange.trim().lowercase().takeIf { it in allowedTimeRanges },
                language = language.trim().takeIf { it.isNotBlank() }
            )

        val results = response.results.take(maxResults.coerceIn(1, MAX_RESULTS_LIMIT))

        if (results.isEmpty() && response.answers.isEmpty() && response.infoboxes.isEmpty()) {
            val down = response.unresponsiveEngines.mapNotNull { it.firstOrNull() }

            // engines suspend themselves on rate limits and CAPTCHAs, and when every one of them is out
            // the empty result set says nothing about the query. reporting it as "nothing found" would
            // have the model tell the user the topic has no coverage.
            if (down.isNotEmpty()) {
                log.warn { "metaSearch: every engine failed query=[$query] unresponsiveEngines=[${down.joinToString()}]" }

                return@suspendToolGuard "The search engines behind this lookup are all rate-limited or unreachable, " +
                        """so nothing came back for "$query" — this says nothing about the topic itself. """ +
                        "Retry with `webSearch` when it is offered, otherwise tell the user search is temporarily unavailable."
            }

            return@suspendToolGuard """No results found for "$query"."""
        }

        buildString {
            appendLine("""Web search results for "$query":""")

            response.answers.forEach { answer ->
                answer.answer.trim().takeIf { it.isNotBlank() }?.let {
                    append("Direct answer: ")
                    appendLine(it.limitTo(MAX_ANSWER_CHARS))
                }
            }

            response.infoboxes.firstOrNull()?.let { infobox ->
                infobox.content.trim().takeIf { it.isNotBlank() }?.let {
                    append(infobox.infobox.trim().ifBlank { "Summary" })
                    append(": ")
                    appendLine(it.limitTo(MAX_ANSWER_CHARS))
                }
            }

            results.forEachIndexed { i, result ->
                append(i + 1)
                append(". ")
                appendLine(result.title)
                append("   URL: ")
                appendLine(result.url)

                result.publishedDate?.let {
                    append("   Published: ")
                    appendLine(it)
                }

                val snippet = result.content.trim().limitTo(MAX_SNIPPET_CHARS)

                if (snippet.isNotBlank()) {
                    append("   ")
                    appendLine(snippet)
                }
            }
        }.trim().limitTo(MAX_SEARCH_OUTPUT_CHARS)
    }

    @Tool
    @LLMDescription(SearxngToolDescriptions.META_SEARCH_IMAGES)
    suspend fun metaSearchImages(
        @LLMDescription(SearxngToolDescriptions.META_SEARCH_IMAGES_QUERY)
        query: String,
        @LLMDescription(SearxngToolDescriptions.META_SEARCH_IMAGES_MAX_RESULTS)
        maxResults: Int = 5
    ): String = suspendToolGuard {
        val response = client.search(query = query, engines = IMAGE_ENGINES)

        val candidates =
            response.results
                .filter { it.imageUrl.isNotBlank() }
                .map { FoundImage(url = it.imageUrl) }

        imageDownloader.deliverImageResults(
            query = query,
            candidates = candidates,
            limit = maxResults.coerceIn(1, MAX_IMAGE_RESULTS),
            outbox = outbox
        )
    }
}
