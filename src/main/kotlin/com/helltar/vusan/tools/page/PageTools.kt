package com.helltar.vusan.tools.page

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard

internal const val MAX_PAGE_TEXT_CHARS = 16_000
private const val MAX_URL_CHARS = 2_048

@Suppress("unused")
class PageTools(private val reader: PageReader) : ToolSet {

    @Tool
    @LLMDescription(PageToolDescriptions.READ_PAGE)
    suspend fun readPage(
        @LLMDescription(PageToolDescriptions.URL)
        url: String,
        @LLMDescription(PageToolDescriptions.OFFSET)
        offset: Int = 0,
    ): String = suspendToolGuard {
        val target = url.requireToolText("URL", MAX_URL_CHARS)
        require(offset >= 0) { "offset must not be negative" }

        val page =
            when (val content = reader.read(target)) {
                is PageContent.Text -> content
                is PageContent.NotReadable ->
                    error("Cannot read $target: ${content.reason}. `downloadFile` sends it to the user as a file instead.")
            }

        check(page.text.isNotBlank()) {
            "The page at $target has no readable text — it may draw its content with scripts, or need a login."
        }

        val total = page.text.length

        require(offset < total) { "offset=$offset is past the end of the page, which has $total characters" }

        // the page is fetched again for every part: cheap, and it keeps the tool free of state to lose
        // between turns. the cut is by character rather than by word so the offsets line up exactly.
        val end = minOf(offset + MAX_PAGE_TEXT_CHARS, total)
        val text = page.text.substring(offset, end)

        buildString {
            appendLine("Use this page as evidence for the answer.")

            append(
                xmlBlock(
                    "page",
                    buildString {
                        appendLine("url: $target")
                        page.title?.let { appendLine("title: $it") }
                        appendLine("characters: $offset to $end of $total")
                        appendLine()
                        append(text)
                    },
                ),
            )

            if (end < total) {
                appendLine()
                append("The page goes on. Continue reading with offset=$end.")
            }
        }
    }
}
