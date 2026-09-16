package com.helltar.vusan.tools.page

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.limitTo
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
    ): String = suspendToolGuard {
        val target = url.requireToolText("URL", MAX_URL_CHARS)

        val page =
            when (val content = reader.read(target)) {
                is PageContent.Text -> content
                is PageContent.NotReadable ->
                    error("Cannot read $target: ${content.reason}. `downloadFile` sends it to the user as a file instead.")
            }

        check(page.text.isNotBlank()) {
            "The page at $target has no readable text — it may draw its content with scripts, or need a login."
        }

        val text = page.text.limitTo(MAX_PAGE_TEXT_CHARS)

        buildString {
            appendLine("Use this page as evidence for the answer.")

            append(
                xmlBlock(
                    "page",
                    buildString {
                        appendLine("url: $target")
                        page.title?.let { appendLine("title: $it") }
                        appendLine()
                        append(text)
                    },
                ),
            )

            if (text.length < page.text.length) {
                appendLine()
                append("The page goes on past $MAX_PAGE_TEXT_CHARS characters; only this much was read.")
            }
        }
    }
}
