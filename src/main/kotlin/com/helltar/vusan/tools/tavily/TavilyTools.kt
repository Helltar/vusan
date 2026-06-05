package com.helltar.vusan.tools.tavily

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.common.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress("unused")
class TavilyTools(private val client: TavilyClient, private val outbox: BotOutbox) : ToolSet {

    private companion object {
        const val MAX_SNIPPET_CHARS = 300
        const val MAX_SEARCH_OUTPUT_CHARS = 3_000
        const val MAX_EXTRACT_CHARS = 6_000
        const val MAX_IMAGE_RESULTS = 10
        const val MAX_PHOTO_BYTES = 10 * 1024 * 1024
        const val MAX_IMAGE_DESCRIPTION_CHARS = 200
        val allowedTopics = setOf("general", "news", "finance")
        val allowedTimeRanges = setOf("day", "week", "month", "year")
        val log = KotlinLogging.logger {}
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
        val response = client.search(query = query, maxResults = capped, includeImages = true)

        if (response.images.isEmpty()) {
            log.warn { "searchImages: provider returned no image candidates query=[$query]" }
            return@suspendToolGuard """No images found for "$query"."""
        }

        var oversize = 0

        val downloaded =
            response.images.take(capped).mapIndexedNotNull { index, image ->
                val bytes =
                    runCatching { client.downloadImage(image.url) }
                        .onFailure { error ->
                            error.rethrowIfCancellation()
                            log.warn(error) { "searchImages: image download failed query=[$query] url=[${image.url}]" }
                        }
                        .getOrNull() ?: return@mapIndexedNotNull null

                if (bytes.size > MAX_PHOTO_BYTES) {
                    oversize++
                    log.warn { "searchImages: image exceeds $MAX_PHOTO_BYTES bytes (got ${bytes.size}) query=[$query] url=[${image.url}]" }
                    return@mapIndexedNotNull null
                }

                BotOutput.Photo(bytes = bytes, filename = imageFilename(query, index, image.url)) to image.description
            }

        if (downloaded.isEmpty()) {
            log.warn {
                "searchImages: none of ${response.images.size} candidate(s) usable " +
                        "(oversize=$oversize, others failed to download) query=[$query]"
            }

            return@suspendToolGuard """Found image URLs for "$query" but failed to download any."""
        }

        val photos = downloaded.map { (photo, _) -> photo }
        val descriptions = downloaded.map { (_, description) -> description?.trim()?.takeIf { it.isNotBlank() } }

        log.info { "searchImages: queued ${photos.size} image(s) for delivery query=[$query]" }

        if (photos.size == 1) {
            outbox.enqueue(photos.single())
        } else {
            outbox.enqueue(BotOutput.PhotoGroup(photos))
        }

        buildString {
            if (photos.size == 1)
                appendLine("""Sent 1 image for "$query".""")
            else
                appendLine("""Sent ${photos.size} images for "$query".""")

            if (descriptions.any { it != null }) {
                appendLine("Image contents (use to answer if the user asks what is in the photo; rewrite in the user's language):")

                descriptions.forEachIndexed { i, description ->
                    append(i + 1)
                    append(". ")
                    appendLine(description?.limitTo(MAX_IMAGE_DESCRIPTION_CHARS) ?: "(no description)")
                }
            }
        }.trim()
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

    private fun imageFilename(query: String, index: Int, url: String): String {
        val extension =
            url.substringAfterLast('.', missingDelimiterValue = "jpg")
                .substringBefore('?')
                .lowercase()
                .take(4)
                .ifBlank { "jpg" }

        val base =
            query.replace(Regex("[^A-Za-z0-9_]+"), "_")
                .trim('_')
                .take(40)
                .ifBlank { "image" }

        return "${base}_${index + 1}.$extension"
    }
}
