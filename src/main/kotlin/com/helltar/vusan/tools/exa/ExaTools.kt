package com.helltar.vusan.tools.exa

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.images.FoundImage
import com.helltar.vusan.tools.images.ImageDownloadClient
import com.helltar.vusan.tools.images.MAX_IMAGE_RESULTS
import com.helltar.vusan.tools.images.MIN_PAGE_PHOTO_SIDE
import com.helltar.vusan.tools.images.deliverImageResults
import com.helltar.vusan.tools.images.pagePhotoCandidates
import com.helltar.vusan.tools.images.photosRefusedReply
import com.helltar.vusan.tools.suspendToolGuard
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class ExaTools(
    private val client: ExaClient,
    private val imageDownloader: ImageDownloadClient,
    private val outbox: BotOutbox,
) : ToolSet {

    @Tool(ExaToolDescriptions.WEB_SEARCH, readOnly = true, copiedToSandbox = true)
    suspend fun webSearch(
        @Arg(ExaToolDescriptions.WEB_SEARCH_QUERY)
        query: String,
        @Arg(ExaToolDescriptions.WEB_SEARCH_MAX_RESULTS)
        maxResults: Int = 5,
        @Arg(ExaToolDescriptions.WEB_SEARCH_CATEGORY)
        category: String = "",
        @Arg(ExaToolDescriptions.WEB_SEARCH_TIME_RANGE)
        timeRange: String = "",
    ): String = suspendToolGuard {
        val scope = category.trim().lowercase().takeIf { it in allowedCategories }

        // exa answers a date filter on these two with a 400; the date goes to the millisecond, as exa writes its
        // own
        val since =
            timeRanges[timeRange.trim().lowercase()]
                ?.takeUnless { scope in undatedCategories }
                ?.let { Instant.fromEpochMilliseconds((Clock.System.now() - it).toEpochMilliseconds()).toString() }

        val response =
            client.search(
                query = query,
                numResults = maxResults.coerceIn(1, MAX_RESULTS),
                highlightChars = MAX_HIGHLIGHT_CHARS,
                category = scope,
                startPublishedDate = since,
            )

        if (response.results.isEmpty()) {
            return@suspendToolGuard """No results found for "$query"."""
        }

        buildString {
            appendLine("""Web search results for "$query":""")

            response.results.forEachIndexed { i, result ->
                append(i + 1)
                append(". ")
                appendLine(result.title?.collapseWhitespaceAndCap(MAX_TITLE_CHARS) ?: result.url)
                append("   URL: ")
                appendLine(result.url)

                result.publishedDate?.let {
                    append("   Published: ")
                    appendLine(it)
                }

                // the excerpts keep the page's markdown layout, which is noise in a list of results
                result.highlights.joinToString(" … ").collapseWhitespaceAndCap(MAX_HIGHLIGHT_CHARS)?.let {
                    append("   ")
                    appendLine(it)
                }
            }
        }.trim().limitTo(MAX_SEARCH_OUTPUT_CHARS)
    }

    @Tool(ExaToolDescriptions.SEARCH_IMAGES)
    suspend fun searchImages(
        @Arg(ExaToolDescriptions.SEARCH_IMAGES_QUERY)
        query: String,
        @Arg(ExaToolDescriptions.SEARCH_IMAGES_MAX_RESULTS)
        maxResults: Int = 5,
        @Arg(ExaToolDescriptions.SEARCH_IMAGES_SEND)
        send: Boolean = true,
        @Arg(ExaToolDescriptions.SEARCH_IMAGES_SPOILER)
        spoiler: Boolean = false,
    ): String = suspendToolGuard {
        if (send) outbox.photosRefusedReply()?.let { return@suspendToolGuard it }

        val response = client.searchImages(query, numResults = IMAGE_PAGES, imagesPerPage = IMAGES_PER_PAGE)

        // a preview is the picture a page chose to stand for it, so every page's goes before any image inside one
        val previews = response.results.mapNotNull { it.image }
        val inPages = response.results.flatMap { it.extras?.imageLinks.orEmpty() }

        imageDownloader.deliverImageResults(
            query = query,
            candidates = pagePhotoCandidates(previews + inPages).map { FoundImage(url = it) },
            limit = maxResults.coerceIn(1, MAX_IMAGE_RESULTS),
            outbox = outbox,
            send = send,
            spoiler = spoiler,
            minShortSide = MIN_PAGE_PHOTO_SIDE,
            skipLookalikes = true,
        )
    }

    @Tool(ExaToolDescriptions.EXTRACT_PAGE_CONTENT, readOnly = true, copiedToSandbox = true)
    suspend fun extractPageContent(
        @Arg(ExaToolDescriptions.EXTRACT_PAGE_URL)
        url: String,
    ): String = suspendToolGuard {
        // one character past the cut tells a page that goes on from one that ends exactly there
        val response = client.contents(url, MAX_EXTRACT_CHARS + 1)
        val text = response.results.firstOrNull()?.text?.trim()

        if (text == null) {
            val reason = response.statuses.firstOrNull()?.error?.let { it.tag ?: it.httpStatusCode?.let { status -> "HTTP $status" } }
            error("Could not extract content from $url: ${reason ?: "unknown error"}. `readPage` reads the same address directly.")
        }

        if (text.isBlank()) {
            error("Page at $url returned empty content. `readPage` reads the same address directly.")
        }

        // shaped as `readPage` answers: the page is evidence inside its own block, never bare text
        buildString {
            appendLine("Use this page as evidence for the answer.")
            append(xmlBlock("page", "url: $url\n\n${text.limitTo(MAX_EXTRACT_CHARS)}"))

            if (text.length > MAX_EXTRACT_CHARS) {
                appendLine()
                append("The page goes on past $MAX_EXTRACT_CHARS characters. `readPage` reads a long page in parts.")
            }
        }
    }

    private companion object {
        const val MAX_RESULTS = 10
        const val MAX_TITLE_CHARS = 200

        // exa sizes the excerpts to each page's relevance, from a few hundred characters to several thousand,
        // so one long page would crowd out the rest without a cap per result
        const val MAX_HIGHLIGHT_CHARS = 800
        const val MAX_SEARCH_OUTPUT_CHARS = 6_000

        // a long article runs past a few thousand characters and this tool cannot page, so the cut must leave
        // a real page's body readable; readPage continues by offset.
        const val MAX_EXTRACT_CHARS = 16_000

        // a picture search reads ten pages, at the price of up to ten results, and a few images from each
        const val IMAGE_PAGES = 10
        const val IMAGES_PER_PAGE = 5

        val allowedCategories = setOf("news", "publication", "company", "people")
        val undatedCategories = setOf("company", "people")
        val timeRanges: Map<String, Duration> = mapOf("day" to 1.days, "week" to 7.days, "month" to 30.days, "year" to 365.days)
    }
}
