package com.helltar.vusan.tools.images

import com.helltar.vusan.common.imageDimensions
import com.helltar.vusan.common.sniffedImageMimeType
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.files.FileDownloadResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.max
import kotlin.math.min

/**
 * Fetches an image a search provider pointed at and rejects anything Telegram would refuse as a
 * photo. Shared by every image search tool, whichever provider produced the URL.
 */
class ImageDownloadClient(private val downloader: FileDownloadClient) {

    /** Returns the bytes, or `null` when the response is not an image Telegram would show. */
    suspend fun download(url: String): ByteArray? {
        val result = downloader.download(url.withScheme(), maxBytes = MAX_PHOTO_BYTES.toLong())
        if (result !is FileDownloadResult.Success) return null
        val bytes = result.bytes

        if (sniffedImageMimeType(bytes) == null) {
            log.info { "download: response is not an image, skipping" }
            return null
        }

        imageDimensions(bytes)?.let { (w, h) ->
            if (!isTelegramPhotoCompatible(w, h)) {
                log.info { "download: incompatible dimensions ${w}x$h, skipping" }
                return null
            }
        }

        return bytes
    }

    private fun isTelegramPhotoCompatible(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return false

        val ratio = max(width, height).toDouble() / min(width, height)

        return ratio <= MAX_ASPECT_RATIO
    }

    private companion object {
        const val MAX_DIMENSION = 10_000
        const val MAX_ASPECT_RATIO = 20.0

        val log = KotlinLogging.logger {}
    }
}

// SearXNG's flickr engine reports protocol-relative image URLs (`//live.staticflickr.com/...`),
// which ktor cannot resolve without a scheme.
private fun String.withScheme(): String =
    if (startsWith("//")) "https:$this" else this
