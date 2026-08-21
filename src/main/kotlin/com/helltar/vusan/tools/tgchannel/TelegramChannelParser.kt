package com.helltar.vusan.tools.tgchannel

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime

// the widget renders the post's own body and the preview of the post it replies to with the same
// `tgme_widget_message_text` class, and the reply preview comes first. only `js-message_text` tells
// them apart, so dropping it makes every reply post report the quoted text as its own.
private const val OWN_TEXT = ".tgme_widget_message_text.js-message_text"

private const val MAX_SHOWN_REACTIONS = 3

internal object TelegramChannelParser {

    private val cssUrlRegex = Regex("""url\((['"]?)(.*?)\1\)""")
    private val compactCountRegex = Regex("""^([\d.,]+)\s*([KMkm]?)$""")

    private val mediaSelectors =
        listOf(
            "photo" to ".tgme_widget_message_photo_wrap",
            "video" to ".tgme_widget_message_video_player",
            "round video" to ".tgme_widget_message_roundvideo",
            "voice" to ".tgme_widget_message_voice",
            "audio" to ".tgme_widget_message_audio",
            "document" to ".tgme_widget_message_document",
            "sticker" to ".tgme_widget_message_sticker_wrap",
            "poll" to ".tgme_widget_message_poll",
            "location" to ".tgme_widget_message_location"
        )

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
            posts = posts,
            olderThanCursor = document.olderThanCursor()
        )
    }

    // Telegram computes its own offset, which for a search page is not simply the oldest id on it.
    private fun Element.olderThanCursor(): Long? =
        selectFirst(".js-messages_more[data-before]")
            ?.attr("data-before")
            ?.toLongOrNull()

    private fun parsePost(element: Element): TelegramChannelPost? {
        val dataPost = element.attr("data-post").trim()

        if (dataPost.isBlank()) return null

        val dateLink = element.selectFirst("a.tgme_widget_message_date")
        val textElement = element.selectFirst(OWN_TEXT)
        val text = textElement?.textWithLineBreaks().orEmpty()
        val mediaKinds = element.mediaKinds()

        if (text.isBlank() && mediaKinds.isEmpty()) return null

        val reactions = element.select(".tgme_reaction").mapNotNull { it.parseReaction() }

        return TelegramChannelPost(
            id = dataPost.substringAfter('/', dataPost),
            url = dateLink?.absUrl("href")?.takeIf { it.isNotBlank() } ?: "https://t.me/$dataPost",
            postedAt = dateLink?.selectFirst("time")?.attr("datetime")?.parseIsoInstant(),
            text = text,
            views = element.selectFirst(".tgme_widget_message_views")?.text()?.trim()?.takeIf { it.isNotBlank() },
            reactionCount = reactions.sumOf { it.second },
            reactions = reactions
                .filter { it.first != null }
                .sortedByDescending { it.second }
                .take(MAX_SHOWN_REACTIONS)
                .joinToString(" ") { "${it.first} ${it.second}" }
                .takeIf { it.isNotBlank() },
            forwardedFrom = element.selectFirst(".tgme_widget_message_forwarded_from_name")?.text()?.trim()
                ?.takeIf { it.isNotBlank() },
            replyTo = element.selectFirst(".tgme_widget_message_reply")?.parseReplyQuote(),
            linkPreview = element.selectFirst(".tgme_widget_message_link_preview")?.parseLinkPreview(),
            mediaKinds = mediaKinds,
            imageUrls = element.extractImageUrls(),
            links = textElement.extractLinks()
        )
    }

    private fun Element.mediaKinds(): List<String> =
        mediaSelectors.mapNotNull { (label, selector) -> label.takeIf { select(selector).isNotEmpty() } }

    /**
     * The count is always the span's own text; the glyph beside it may be a plain emoji, a paid
     * star reaction, or a `<tg-emoji>` custom one that carries an id and no readable character at
     * all. The last kind still counts toward the total, it just has nothing to show.
     */
    private fun Element.parseReaction(): Pair<String?, Int>? {
        val count = ownText().trim().parseCompactCount() ?: return null

        val emoji =
            if (hasClass("tgme_reaction_paid")) "⭐"
            else selectFirst("i.emoji b")?.text()?.trim()?.takeIf { it.isNotBlank() }

        return emoji to count
    }

    private fun String.parseCompactCount(): Int? {
        val match = compactCountRegex.matchEntire(this) ?: return null
        val value = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null

        val multiplier =
            when (match.groupValues[2].lowercase()) {
                "k" -> 1_000
                "m" -> 1_000_000
                else -> 1
            }

        return (value * multiplier).toInt()
    }

    private fun Element.parseReplyQuote(): String? {
        val author = selectFirst(".tgme_widget_message_author_name")?.text()?.trim().orEmpty()
        val quoted = selectFirst(".js-message_reply_text")?.text()?.trim().orEmpty()

        return listOf(author, quoted)
            .filter { it.isNotBlank() }
            .joinToString(": ")
            .takeIf { it.isNotBlank() }
    }

    private fun Element.parseLinkPreview(): String? =
        listOf(
            ".tgme_widget_message_link_preview_site_name",
            ".tgme_widget_message_link_preview_title",
            ".tgme_widget_message_link_preview_description"
        )
            .mapNotNull { selectFirst(it)?.text()?.trim()?.takeIf { text -> text.isNotBlank() } }
            .joinToString(" — ")
            .takeIf { it.isNotBlank() }

    private fun Element?.extractLinks(): List<String> =
        this
            ?.select("a[href]")
            ?.map { it.absUrl("href") }
            ?.filter { it.isHttpUrl() }
            ?.distinct()
            .orEmpty()

    private fun Element.extractImageUrls(): List<String> =
        select(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_thumb")
            .mapNotNull { it.attr("style").extractCssUrl() }
            .filter { it.isHttpUrl() }
            .distinct()

    private fun String.extractCssUrl(): String? {
        val raw = cssUrlRegex.find(this)?.groupValues?.getOrNull(2)?.trim().orEmpty()

        if (raw.isBlank()) return null

        return runCatching { URI(raw).toString() }.getOrNull() ?: raw
    }

    // the widget stamps an ISO offset ("2026-08-21T16:09:52+00:00"), which Instant.parse rejects.
    private fun String.parseIsoInstant(): Instant? =
        takeIf { it.isNotBlank() }
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun Element.textWithLineBreaks(): String {
        val clone = clone()
        clone.select("br").forEach { br ->
            br.before(TextNode("\n"))
            br.remove()
        }

        return clone
            .wholeText()
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

private fun String.isHttpUrl(): Boolean = startsWith("http://") || startsWith("https://")
