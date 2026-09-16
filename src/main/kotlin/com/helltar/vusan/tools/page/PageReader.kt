package com.helltar.vusan.tools.page

import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.files.FileDownloadResult
import io.ktor.http.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayInputStream

// a page is read for its words, so the cap is well under what a download may be: a document past it is
// not an article, and the text cap below cuts a long one before any of this matters.
private const val PAGE_LIMIT = 4 * 1024 * 1024L

// what a page holds beside its content. `article` and `main` are dropped from this list on purpose:
// they are where the content is, and the boilerplate tags are removed from inside them too.
private const val BOILERPLATE_TAGS = "script, style, noscript, template, svg, iframe, nav, header, footer, aside, form"
private const val CONTENT_ROOTS = "article, main, [role=main]"
private const val BLOCK_TAGS =
    "p, div, section, article, main, h1, h2, h3, h4, h5, h6, ul, ol, pre, blockquote, table, tr, dd, dt, figcaption, hr"

private val BLANK_LINES = Regex("\\n{3,}")
private val INLINE_WHITESPACE = Regex("[ \\t\\u00a0]+")

sealed interface PageContent {

    /** Readable text, with [title] from the document when it had one. */
    class Text(val title: String?, val text: String) : PageContent

    /** Something a reader cannot turn into words: a binary, or a document past the size cap. */
    class NotReadable(val reason: String) : PageContent
}

/**
 * Reads a public web page into text. HTML is reduced to its content — the article or main element
 * when the page marks one, with scripts, navigation and chrome removed — and plain text is passed
 * through. Everything else is reported as not readable rather than decoded into noise.
 */
class PageReader(private val downloader: FileDownloadClient) {

    suspend fun read(url: String): PageContent {
        val downloaded =
            when (val result = downloader.download(url, maxBytes = PAGE_LIMIT)) {
                is FileDownloadResult.Success -> result
                is FileDownloadResult.TooLarge ->
                    return PageContent.NotReadable("the document is larger than ${PAGE_LIMIT / 1024 / 1024} MB, which no page is")
            }

        val contentType = downloaded.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        val charset = downloaded.charset?.let { runCatching { charset(it) }.getOrNull() }

        return when {
            contentType == null || contentType.isHtml() ->
                htmlToText(Jsoup.parse(ByteArrayInputStream(downloaded.bytes), charset?.name(), downloaded.url.toString()))

            contentType.isText() ->
                PageContent.Text(title = null, text = downloaded.bytes.toString(charset ?: Charsets.UTF_8).normalized())

            else -> PageContent.NotReadable("it is `${contentType.contentType}/${contentType.contentSubtype}`, not a page")
        }
    }

    private fun htmlToText(document: Document): PageContent {
        document.select(BOILERPLATE_TAGS).remove()
        val root = document.selectFirst(CONTENT_ROOTS) ?: document.body() ?: document

        return PageContent.Text(
            title = document.title().trim().takeIf { it.isNotEmpty() },
            text = root.toText().normalized(),
        )
    }
}

private fun ContentType.isHtml(): Boolean =
    match(ContentType.Text.Html) || match(ContentType.Application.Xml.withSubtype("xhtml+xml"))

private fun ContentType.isText(): Boolean =
    contentType == "text" || match(ContentType.Application.Json) || match(ContentType.Application.Xml)

private fun ContentType.withSubtype(subtype: String): ContentType = ContentType(contentType, subtype)

// jsoup's `text()` folds a whole page onto one line, so the structure is rebuilt by hand: a line
// break per block element and per `br`, a bullet per list item, everything else collapsed.
private fun Element.toText(): String {
    val out = StringBuilder()

    traverse(object : NodeVisitor {
        override fun head(node: Node, depth: Int) {
            when (node) {
                is TextNode -> out.append(node.text())
                is Element -> when {
                    node.tagName() == "br" -> out.append('\n')
                    node.tagName() == "li" -> out.append("\n- ")
                    node.`is`(BLOCK_TAGS) -> out.append('\n')
                }
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node is Element && node.`is`(BLOCK_TAGS)) out.append('\n')
        }
    })

    return out.toString()
}

private fun String.normalized(): String =
    lineSequence()
        .map { it.replace(INLINE_WHITESPACE, " ").trim() }
        .joinToString("\n")
        .replace(BLANK_LINES, "\n\n")
        .trim()
