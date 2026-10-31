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

// a page is read for its words, so the cap is far under the download limit: a larger document is no article,
// and the text cap cuts a long one anyway.
private const val PAGE_LIMIT = 4 * 1024 * 1024L

// what a page holds beside its content. `article` and `main` are left out on purpose: they hold the content,
// and boilerplate inside them is removed too.
private const val BOILERPLATE_TAGS = "script, style, noscript, template, svg, iframe, nav, header, footer, aside, form"
private const val CONTENT_ROOTS = "article, main, [role=main]"

// what a page hides from readers but would show a model: `hidden`, `aria-hidden`, or an inline style moving
// the element off screen. a stylesheet can hide more, which only a browser knows; these are the forms planted
// instructions use.
private const val HIDDEN_ELEMENTS =
    "[hidden], [aria-hidden=true], [style*=display:none], [style*=display: none], [style*=visibility:hidden], [style*=visibility: hidden]"
private const val BLOCK_TAGS =
    "p, div, section, article, main, h1, h2, h3, h4, h5, h6, ul, ol, pre, blockquote, table, tr, dd, dt, figcaption, hr"

private val BLANK_LINES = Regex("\\n{3,}")
private val INLINE_WHITESPACE = Regex("[ \\t\\u00a0]+")

sealed interface PageContent {

    /** Readable text, with [title] from the document when it had one. */
    class Text(val title: String?, val text: String) : PageContent

    /** Something that cannot become words: a binary, or a document past the size cap. */
    class NotReadable(val reason: String) : PageContent
}

/**
 * Reads a public web page into text. HTML is reduced to its content (the article or main element when marked,
 * without scripts, navigation and chrome), plain text passes through, and anything else is reported
 * unreadable rather than decoded into noise.
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
        document.select(HIDDEN_ELEMENTS).remove()
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

// jsoup's `text()` folds a page onto one line, so structure is rebuilt by hand: a break per block element and
// `br`, a bullet per list item, the rest collapsed.
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
