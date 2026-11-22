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
import java.net.URI
import java.nio.charset.Charset

// a page is read for its words, so the cap is far under the download limit: a larger document is no article,
// and the text cap cuts a long one anyway.
private const val PAGE_LIMIT = 4 * 1024 * 1024L
private const val TOO_LARGE = "the document is larger than ${PAGE_LIMIT / 1024 / 1024} MB, which no page is"

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
private val SRCSET_DESCRIPTOR = Regex("""(\d+(?:\.\d+)?)[wx]""")
private val INLINE_WHITESPACE = Regex("[ \\t\\u00a0]+")

// where a page names the picture that stands for it, in its head
private const val PREVIEW_IMAGES =
    "meta[property=og:image], meta[property=og:image:url], meta[property=og:image:secure_url], " +
            "meta[name=twitter:image], meta[name=twitter:image:src], meta[property=twitter:image]"

// where lazy loaders keep a picture's real address while `src` holds a placeholder
private val LAZY_SOURCES = listOf("data-src", "data-lazy-src", "data-original", "data-url")

// more than a few albums' worth of tries: the delivery stops long before
private const val MAX_IMAGE_URLS = 40

sealed interface PageContent {

    /** Readable text, with [title] from the document when it had one. */
    class Text(val title: String?, val text: String) : PageContent

    /** Something that cannot become words: a binary, or a document past the size cap. */
    class NotReadable(val reason: String) : PageContent
}

sealed interface PageImages {

    /** The image URLs a page offers, best first, with [title] from the document when it had one. */
    class Found(val title: String?, val urls: List<String>) : PageImages

    /** Something that is neither a page nor an image. */
    class NotReadable(val reason: String) : PageImages
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
                is FileDownloadResult.TooLarge -> return PageContent.NotReadable(TOO_LARGE)
            }

        val contentType = downloaded.parsedContentType()
        val charset = downloaded.parsedCharset()

        return when {
            contentType == null || contentType.isHtml() -> htmlToText(downloaded.toDocument())
            contentType.isText() -> PageContent.Text(title = null, text = downloaded.bytes.toString(charset ?: Charsets.UTF_8).normalized())
            else -> PageContent.NotReadable("it is `${contentType.contentType}/${contentType.contentSubtype}`, not a page")
        }
    }

    /**
     * The pictures of a public web page: the preview its head names, then the images of its content, without
     * those in navigation and chrome. A link straight to an image is its own one picture.
     */
    suspend fun images(url: String): PageImages {
        val downloaded =
            when (val result = downloader.download(url, maxBytes = PAGE_LIMIT)) {
                is FileDownloadResult.Success -> result
                is FileDownloadResult.TooLarge -> return PageImages.NotReadable(TOO_LARGE)
            }

        val contentType = downloaded.parsedContentType()

        return when {
            contentType?.contentType == "image" -> PageImages.Found(title = null, urls = listOf(downloaded.url.toString()))

            contentType == null || contentType.isHtml() -> {
                val document = downloaded.toDocument()
                PageImages.Found(title = document.title().trim().takeIf { it.isNotEmpty() }, urls = imageUrlsOf(document))
            }

            else -> PageImages.NotReadable("it is `${contentType.contentType}/${contentType.contentSubtype}`, not a page")
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

private fun FileDownloadResult.Success.parsedContentType(): ContentType? =
    contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }

private fun FileDownloadResult.Success.parsedCharset(): Charset? =
    charset?.let { runCatching { charset(it) }.getOrNull() }

private fun FileDownloadResult.Success.toDocument(): Document =
    Jsoup.parse(ByteArrayInputStream(bytes), parsedCharset()?.name(), url.toString())

// the preview first, as the picture the page chose to stand for it; chrome goes before the content is read, as
// for text, so a site's logos and its other articles' thumbnails stay out
private fun imageUrlsOf(document: Document): List<String> {
    val previews =
        document.select(PREVIEW_IMAGES).map { it.absUrl("content") } +
                document.select("link[rel=image_src]").map { it.absUrl("href") }

    document.select(BOILERPLATE_TAGS).remove()
    document.select(HIDDEN_ELEMENTS).remove()
    val root = document.selectFirst(CONTENT_ROOTS) ?: document.body() ?: document
    // a `picture`'s `source` offers the same photo again in other formats; its `img` is the fallback every
    // browser takes, and one copy is enough
    val inContent = root.select("img").mapNotNull { it.pictureUrl() }

    return (previews + inContent).filter { it.isNotEmpty() }.distinct().take(MAX_IMAGE_URLS)
}

// the largest a `srcset` offers, then a lazy loader's real address, then `src`
private fun Element.pictureUrl(): String? {
    val fromSet =
        (attr("srcset").ifBlank { attr("data-srcset") })
            .largestInSrcset()
            ?.let { runCatching { URI(baseUri()).resolve(it.replace(" ", "%20")).toString() }.getOrNull() }

    return fromSet
        ?: LAZY_SOURCES.firstNotNullOfOrNull { absUrl(it).takeIf(String::isNotEmpty) }
        ?: absUrl("src").takeIf(String::isNotEmpty)
}

/**
 * The address of the widest candidate in a `srcset`: `a.jpg 480w, b.jpg 960w` or `a.jpg 1x, b.jpg 2x`, a
 * missing descriptor counting as `1`. Split on whitespace rather than commas, since an image CDN's address
 * may carry commas of its own (`/w_400,h_300/a.jpg`).
 */
internal fun String.largestInSrcset(): String? {
    val tokens = trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val candidates = mutableListOf<Pair<String, Double>>()
    var i = 0

    while (i < tokens.size) {
        val token = tokens[i++]
        val url = token.trimEnd(',')
        var size = 1.0

        if (!token.endsWith(',') && i < tokens.size) {
            SRCSET_DESCRIPTOR.matchEntire(tokens[i].trimEnd(','))?.let { match ->
                size = match.groupValues[1].toDouble()
                i++
            }
        }

        if (url.isNotEmpty()) candidates += url to size
    }

    return candidates.maxByOrNull { it.second }?.first
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
