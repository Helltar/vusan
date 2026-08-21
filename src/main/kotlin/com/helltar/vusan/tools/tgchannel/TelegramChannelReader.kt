package com.helltar.vusan.tools.tgchannel

import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

/**
 * Reads a public channel's posts, optionally only those inside a time window or matching a search.
 *
 * One preview page carries twenty posts, so anything wider walks `?before=` backwards: with a window
 * until a page ends older than the cutoff, otherwise until enough posts are in hand or Telegram stops
 * offering an earlier batch. A day of a very busy channel is about five requests.
 *
 * Vision is spent per **post**, never per image: a post is described whole or not at all, because half
 * an album says less than none of it. A meme channel where the picture *is* the post costs a handful
 * of calls a day, while a news channel whose text already carries the story is left alone.
 */
class TelegramChannelReader(
    private val client: TelegramChannelClient,
    private val imageDescriber: TelegramChannelImageDescriber?,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    private companion object {
        const val MAX_PAGES = 10
        const val DEFAULT_POSTS = 12
        const val MAX_POSTS = 200

        // Telegram caps message text at 4096, and the longest posts measured on real channels sit
        // near 2000, so this leaves ordinary posts whole and trims only a rare longread.
        const val MAX_POST_TEXT_CHARS = 2_500

        // a day of the busiest channels measured renders at ~32k with metadata, so a full day always
        // comes back whole and only a multi-day window starts dropping its oldest posts. The run's
        // own tool budget in `AgentFactory` is what bounds this against the model's context.
        const val MAX_OUTPUT_CHARS = 48_000

        const val MAX_IMAGES_TO_DESCRIBE = 24
        const val MAX_IMAGE_DESCRIPTION_CHARS = 600
        const val VISION_CONCURRENCY = 4

        const val MAX_LINKS_PER_POST = 6
        const val MAX_LINK_PREVIEW_CHARS = 300
        const val MAX_REPLY_QUOTE_CHARS = 160

        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val log = KotlinLogging.logger {}
    }

    suspend fun read(
        channel: String,
        window: Duration?,
        query: String,
        maxPosts: Int,
        describeImages: Boolean,
        imageFocus: String,
        now: Instant = Instant.now()
    ): String {
        val reference = TelegramChannelReference.parse(channel)
        val cutoff = window?.let { now.minusSeconds(it.inWholeSeconds) }
        val walk = walk(reference, query, cutoff, postLimit(maxPosts, window))

        if (!walk.previewAvailable) return noPreviewResult(reference)
        if (walk.posts.isEmpty()) return emptyResult(reference, query, cutoff, now, walk)

        val descriptions =
            imageDescriber
                ?.takeIf { describeImages }
                ?.let { describePostImages(walk.posts, imageFocus, it) }
                .orEmpty()

        val rendered = render(walk.posts, descriptions)

        return buildString {
            append(header(reference, walk, rendered, query, cutoff, now))
            appendLine()
            appendLine()
            append(xmlBlock("channel_posts", rendered.text))
        }
    }

    private fun postLimit(maxPosts: Int, window: Duration?): Int =
        when {
            maxPosts > 0 -> maxPosts.coerceAtMost(MAX_POSTS)
            window != null -> MAX_POSTS
            else -> DEFAULT_POSTS
        }

    private class Walk(
        val title: String,
        val posts: List<TelegramChannelPost>,
        val newestSeen: Instant?,
        val previewAvailable: Boolean,
        /** True when the walk stopped on a cap with the window not yet fully covered. */
        val truncated: Boolean
    )

    private suspend fun walk(
        reference: TelegramChannelReference,
        query: String,
        cutoff: Instant?,
        limit: Int
    ): Walk {
        val collected = mutableListOf<TelegramChannelPost>()
        var title = "@${reference.username}"
        var newestSeen: Instant? = null
        var before: Long? = null
        var pages = 0
        var reachedStart = false
        var passedCutoff = false

        while (pages < MAX_PAGES) {
            val page = client.read(reference, before = before, query = query)
            pages++

            if (!page.previewAvailable) {
                return Walk(title, emptyList(), null, previewAvailable = false, truncated = false)
            }

            if (pages == 1) {
                title = page.title
                newestSeen = page.posts.firstNotNullOfOrNull { it.postedAt }
            }

            if (page.posts.isEmpty()) {
                reachedStart = true
                break
            }

            collected += if (cutoff == null) page.posts else page.posts.filter { it.postedAt?.isBefore(cutoff) != true }
            passedCutoff = cutoff != null && page.posts.mapNotNull { it.postedAt }.any { it.isBefore(cutoff) }

            if (passedCutoff || collected.size >= limit) break

            val cursor = page.olderThanCursor

            if (cursor == null) {
                reachedStart = true
                break
            }

            before = cursor
        }

        return Walk(
            title = title,
            posts = collected.take(limit),
            newestSeen = newestSeen,
            previewAvailable = true,
            // the window is only fully covered once the walk saw a post older than it, or ran out of
            // channel; stopping on the page or post cap leaves an unread earlier part.
            truncated = cutoff != null && !passedCutoff && !reachedStart
        )
    }

    private suspend fun describePostImages(
        posts: List<TelegramChannelPost>,
        focus: String,
        describer: TelegramChannelImageDescriber
    ): Map<String, List<String>> {
        val selected = selectPostsForVision(posts, MAX_IMAGES_TO_DESCRIBE)

        if (selected.isEmpty()) return emptyMap()

        val gate = Semaphore(VISION_CONCURRENCY)

        val described =
            coroutineScope {
                selected
                    .flatMap { post -> post.imageUrls.map { post to it } }
                    .map { (post, imageUrl) ->
                        async { post.id to gate.withPermit { describe(describer, post, imageUrl, focus) } }
                    }
                    .awaitAll()
            }

        return described.groupBy({ it.first }, { it.second })
    }

    private suspend fun describe(
        describer: TelegramChannelImageDescriber,
        post: TelegramChannelPost,
        imageUrl: String,
        focus: String
    ): String =
        runCatching {
            describer.describe(client.downloadImage(imageUrl), post, focus).limitTo(MAX_IMAGE_DESCRIPTION_CHARS)
        }.getOrElse { t ->
            t.rethrowIfCancellation()
            log.warn { "channel image vision failed for post=[${post.url}]: ${t.message}" }
            "Could not inspect image: ${t.message ?: t::class.simpleName}"
        }

    private class Rendered(val text: String, val shown: Int, val total: Int)

    private fun render(
        posts: List<TelegramChannelPost>,
        descriptions: Map<String, List<String>>
    ): Rendered {
        val body = StringBuilder()
        var shown = 0

        for (post in posts) {
            val block = renderPost(shown + 1, post, descriptions[post.id])

            if (shown > 0 && body.length + block.length > MAX_OUTPUT_CHARS) break

            body.append(block)
            shown++
        }

        return Rendered(body.toString(), shown, posts.size)
    }

    private fun renderPost(number: Int, post: TelegramChannelPost, descriptions: List<String>?): String =
        buildString {
            append(number)
            append(". post ")
            append(post.id)
            post.postedAt?.let { append(" | ${stamp(it)}") }
            post.views?.let { append(" | views $it") }
            post.reactionCount.takeIf { it > 0 }?.let { append(" | reactions $it") }
            post.reactions?.let { append(" | $it") }
            appendLine()
            appendLine("url: ${post.url}")

            post.forwardedFrom?.let { appendLine("forwarded from: $it") }
            post.replyTo?.let { appendLine("replying to: ${it.limitTo(MAX_REPLY_QUOTE_CHARS)}") }

            if (post.mediaKinds.isNotEmpty()) {
                append("media: ${post.mediaKinds.joinToString(", ")}")
                if (post.imageUrls.size > 1) append(" (${post.imageUrls.size} images)")
                appendLine()
            }

            appendLine("text:")
            appendLine(post.text.trim().limitTo(MAX_POST_TEXT_CHARS).ifBlank { "[no text, media only]" })

            post.linkPreview?.let { appendLine("link preview: ${it.limitTo(MAX_LINK_PREVIEW_CHARS)}") }

            descriptions?.forEachIndexed { index, description -> appendLine("image ${index + 1}: $description") }

            val links = post.links.take(MAX_LINKS_PER_POST)

            if (links.isNotEmpty()) {
                appendLine("links: ${links.joinToString(" ")}")
                if (post.links.size > links.size) appendLine("...and ${post.links.size - links.size} more links")
            }

            appendLine()
        }

    // the count above the block covers every post the walk kept, while the block under it may hold
    // fewer; labelling it keeps "how many did they post" off a tally of visible entries.
    private fun header(
        reference: TelegramChannelReference,
        walk: Walk,
        rendered: Rendered,
        query: String,
        cutoff: Instant?,
        now: Instant
    ): String =
        buildString {
            append("Telegram channel @${reference.username} — ${walk.title}")
            appendLine()
            append("Source: ${reference.webPreviewUrl(query = query)}")

            if (query.isNotBlank()) {
                appendLine()
                append("Search: posts matching `$query`.")
            }

            if (cutoff != null) {
                appendLine()
                append("Window: ${stamp(cutoff)} .. ${stamp(now)} ${zone.id}.")
                append(" Posts in it: ${rendered.total} (exact for the part covered).")

                if (walk.truncated) {
                    appendLine()
                    append("The channel posts faster than the window could be walked; covered back to ")
                    append(walk.posts.lastOrNull()?.postedAt?.let { stamp(it) } ?: "the newest page")
                    append(" only.")
                }
            } else {
                appendLine()
                append("Posts read: ${rendered.total} recent post(s).")
            }

            appendLine()
            append("Ordered newest first.")

            if (rendered.shown < rendered.total) {
                append(" Only the newest ${rendered.shown} fit this tool's size limit; ")
                append("narrow the window to read the earlier part.")
            }
        }

    private fun noPreviewResult(reference: TelegramChannelReference): String =
        "@${reference.username} has no public web preview, so its posts cannot be read. " +
                "The name may belong to a bot, a user, or a group rather than a channel, " +
                "the channel may be private or removed, or it may have turned the preview off."

    private fun emptyResult(
        reference: TelegramChannelReference,
        query: String,
        cutoff: Instant?,
        now: Instant,
        walk: Walk
    ): String =
        when {
            query.isNotBlank() ->
                "No posts matching `$query` in @${reference.username}."

            cutoff != null ->
                buildString {
                    append("@${reference.username} posted nothing between ${stamp(cutoff)} and ${stamp(now)}.")
                    walk.newestSeen?.let { append(" Its newest post is from ${stamp(it)}.") }
                }

            else ->
                "No public posts found for @${reference.username}. " +
                        "The channel may be empty, age-restricted, or blocked by Telegram web preview."
        }

    private fun stamp(instant: Instant): String =
        TIMESTAMP.format(ZonedDateTime.ofInstant(instant, zone))
}

// below this a caption cannot be carrying the post on its own, so the image is the content and
// describing it buys the most.
private const val TEXT_CARRIES_POST_CHARS = 120

/**
 * Whole posts, in reading order, until [allowance] images are spoken for. A post is taken with all of
 * its images or not at all, so an album is never described half way. When everything fits, that is
 * everything; when it does not, posts whose text is too short to be carrying them come first, then
 * the ones the channel's own readers reacted to most.
 */
internal fun selectPostsForVision(
    posts: List<TelegramChannelPost>,
    allowance: Int
): List<TelegramChannelPost> {
    val candidates = posts.filter { it.imageUrls.isNotEmpty() }

    if (candidates.sumOf { it.imageUrls.size } <= allowance) return candidates

    val ranked =
        candidates.sortedWith(
            compareBy<TelegramChannelPost> { it.text.length >= TEXT_CARRIES_POST_CHARS }
                .thenByDescending { it.reactionCount }
        )

    val taken = mutableSetOf<String>()
    var remaining = allowance

    for (post in ranked) {
        if (post.imageUrls.size > remaining) continue

        taken += post.id
        remaining -= post.imageUrls.size

        if (remaining == 0) break
    }

    return candidates.filter { it.id in taken }
}
