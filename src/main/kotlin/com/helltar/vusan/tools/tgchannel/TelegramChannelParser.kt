package com.helltar.vusan.tools.tgchannel

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI

internal object TelegramChannelParser {

    private val cssUrlRegex = Regex("""url\((['"]?)(.*?)\1\)""")

    fun parse(html: String, username: String, url: String, maxPosts: Int): TelegramChannelPage {
        val document = Jsoup.parse(html, url)
        val title = document
            .selectFirst(".tgme_channel_info_header_title span")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "@$username"

        val posts = document
            .select(".tgme_widget_message[data-post]")
            .mapNotNull { parsePost(it) }
            .takeLast(maxPosts)
            .asReversed()

        return TelegramChannelPage(
            username = username,
            title = title,
            url = url,
            posts = posts
        )
    }

    private fun parsePost(element: Element): TelegramChannelPost? {
        val dataPost = element.attr("data-post").trim()

        if (dataPost.isBlank()) return null

        val id = dataPost.substringAfter('/', dataPost)
        val dateLink = element.selectFirst("a.tgme_widget_message_date")
        val postUrl = dateLink?.absUrl("href")?.takeIf { it.isNotBlank() } ?: "https://t.me/$dataPost"
        val textElement = element.selectFirst(".tgme_widget_message_text")
        val text = textElement?.textWithLineBreaks().orEmpty()
        val links = textElement
            ?.select("a[href]")
            ?.mapNotNull { it.absUrl("href").takeIf { href -> href.startsWith("http://") || href.startsWith("https://") } }
            ?.distinct()
            .orEmpty()

        val hasMedia = element.select(
            ".tgme_widget_message_photo_wrap, " +
                    ".tgme_widget_message_video_player, " +
                    ".tgme_widget_message_document, " +
                    ".tgme_widget_message_voice, " +
                    ".tgme_widget_message_roundvideo"
        ).isNotEmpty()

        if (text.isBlank() && !hasMedia) return null

        val imageUrls = element.extractImageUrls()

        return TelegramChannelPost(
            id = id,
            url = postUrl,
            datetime = dateLink?.selectFirst("time")?.attr("datetime")?.takeIf { it.isNotBlank() },
            text = text,
            views = element.selectFirst(".tgme_widget_message_views")?.text()?.trim()?.takeIf { it.isNotBlank() },
            hasMedia = hasMedia,
            imageUrls = imageUrls,
            links = links
        )
    }

    private fun Element.extractImageUrls(): List<String> =
        select(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_thumb")
            .mapNotNull { it.attr("style").extractCssUrl() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()

    private fun String.extractCssUrl(): String? {
        val raw = cssUrlRegex.find(this)?.groupValues?.getOrNull(2)?.trim().orEmpty()

        if (raw.isBlank()) return null

        return runCatching { URI(raw).toString() }.getOrNull() ?: raw
    }

    private fun Element.textWithLineBreaks(): String {
        val clone = clone()
        clone.select("br").forEach { br ->
            br.before(TextNode("\n"))
            br.remove()
        }

        return clone
            .wholeText()
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
