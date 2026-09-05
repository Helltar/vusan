package com.helltar.vusan.tools.images

import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import io.github.oshai.kotlinlogging.KotlinLogging

const val MAX_IMAGE_RESULTS = 10

internal const val MAX_PHOTO_BYTES = 10 * 1024 * 1024
private const val MAX_IMAGE_DESCRIPTION_CHARS = 200
private const val MAX_FILENAME_BASE_CHARS = 40

// providers hand back dead links and hotlink-protected hosts, so a candidate list is worked through
// until enough images survive rather than stopping after one attempt each.
private const val ATTEMPTS_PER_IMAGE = 4

private val log = KotlinLogging.logger("ImageSearch")

/** One image a search provider found; [description] is what that provider says is in it. */
data class FoundImage(val url: String, val description: String? = null)

/**
 * Downloads [candidates] in order, keeps up to [limit] that Telegram can show as a photo, queues
 * them as a single photo or a media group, and returns the text the tool reports back to the model.
 */
suspend fun ImageDownloadClient.deliverImageResults(
    query: String,
    candidates: List<FoundImage>,
    limit: Int,
    outbox: BotOutbox
): String {
    if (candidates.isEmpty()) {
        log.warn { "provider returned no image candidates query=[$query]" }
        return """No images found for "$query"."""
    }

    val delivered = mutableListOf<Pair<BotOutput.Photo, String?>>()
    var attempts = 0
    var oversize = 0

    for (candidate in candidates) {
        if (delivered.size >= limit || attempts >= limit * ATTEMPTS_PER_IMAGE) break

        attempts++

        val bytes =
            runCatching { download(candidate.url) }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    log.warn(error) { "image download error query=[$query] url=[${candidate.url}]" }
                }
                .getOrNull() ?: continue

        if (bytes.size > MAX_PHOTO_BYTES) {
            oversize++
            log.warn { "image exceeds $MAX_PHOTO_BYTES bytes (got ${bytes.size}) query=[$query] url=[${candidate.url}]" }
            continue
        }

        val filename = imageFilename(query, delivered.size, candidate.url)

        delivered += BotOutput.Photo(bytes = bytes, filename = filename) to candidate.description
    }

    if (delivered.isEmpty()) {
        log.warn {
            "none of $attempts candidate(s) usable " +
                    "(oversize=$oversize, rest not images or failed to download) query=[$query]"
        }

        return """Found image URLs for "$query" but failed to download any."""
    }

    val photos = delivered.map { (photo, _) -> photo }
    val descriptions = delivered.map { (_, description) -> description?.trim()?.takeIf { it.isNotBlank() } }

    log.info { "queued ${photos.size} image(s) for delivery after $attempts attempt(s) query=[$query]" }

    if (photos.size == 1) {
        outbox.enqueue(photos.single())
    } else {
        outbox.enqueue(BotOutput.PhotoGroup(photos))
    }

    return buildString {
        if (photos.size == 1)
            appendLine("""Sent 1 image for "$query".""")
        else
            appendLine("""Sent ${photos.size} images for "$query".""")

        if (descriptions.any { it != null }) {
            appendLine("Image contents (use to answer if the user asks what is in the photo; rewrite in the user's language):")

            descriptions.forEachIndexed { i, description ->
                append(i + 1)
                append(". ")
                appendLine(description?.limitTo(MAX_IMAGE_DESCRIPTION_CHARS) ?: "(no description)")
            }
        }
    }.trim()
}

private fun imageFilename(query: String, index: Int, url: String): String {
    val extension =
        url.substringAfterLast('.', missingDelimiterValue = "jpg")
            .substringBefore('?')
            .lowercase()
            .take(4)
            .ifBlank { "jpg" }

    val base =
        query.replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .take(MAX_FILENAME_BASE_CHARS)
            .ifBlank { "image" }

    return "${base}_${index + 1}.$extension"
}
