package com.helltar.vusan.tools.codexsearch

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class CodexSearchTools(private val client: CodexSearchClient) : ToolSet {

    @Tool(CodexSearchToolDescriptions.ANSWER_FROM_WEB)
    suspend fun answerFromWeb(
        @Arg(CodexSearchToolDescriptions.ANSWER_FROM_WEB_QUESTION)
        question: String,
    ): String = suspendToolGuard {
        val answer = client.search(question.requireToolText("Question", MAX_QUESTION_CHARS))

        buildString {
            appendLine("Use this answer, researched on the web just now:")
            append(xmlBlock("web_answer", answer.text.limitTo(MAX_ANSWER_CHARS)))

            if (answer.sources.isNotEmpty()) {
                appendLine()
                append("Sources:")

                answer.sources.take(MAX_SOURCES).forEachIndexed { i, source ->
                    appendLine()
                    append(i + 1)
                    append(". ")

                    source.title.collapseWhitespaceAndCap(MAX_TITLE_CHARS)?.let {
                        append(it)
                        append(" — ")
                    }

                    append(source.url)
                }
            }
        }
    }

    private companion object {
        const val MAX_QUESTION_CHARS = 1_000
        const val MAX_ANSWER_CHARS = 6_000
        const val MAX_SOURCES = 10
        const val MAX_TITLE_CHARS = 120
    }
}
