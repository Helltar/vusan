package com.helltar.vusan.tools.images

import com.helltar.vusan.common.imageDimensions
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

    private companion object {
        const val MAX_DIMENSION = 10_000
        const val MAX_ASPECT_RATIO = 20.0

        val log = KotlinLogging.logger {}
    }

    /** Returns the bytes, or `null` when the response is not an image Telegram would show. */
    suspend fun download(url: String): ByteArray? {
        val result = downloader.download(url.withScheme(), maxBytes = MAX_PHOTO_BYTES.toLong())
        if (result !is FileDownloadResult.Success) return null
        val bytes = result.bytes

        if (!looksLikeImage(bytes)) {
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

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false

        fun b(i: Int) =
            bytes[i].toInt() and 0xFF

        return when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> true
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> true
            b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38 -> true
            b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> true
            b(0) == 0x42 && b(1) == 0x4D -> true
            else -> false
        }
    }
}

// SearXNG's flickr engine reports protocol-relative image URLs (`//live.staticflickr.com/...`),
// which ktor cannot resolve without a scheme.
private fun String.withScheme(): String =
    if (startsWith("//")) "https:$this" else this
