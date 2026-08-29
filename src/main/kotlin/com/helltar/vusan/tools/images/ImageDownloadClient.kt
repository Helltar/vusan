package com.helltar.vusan.tools.images

import com.helltar.vusan.common.imageDimensions
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.math.max
import kotlin.math.min

/**
 * Fetches an image a search provider pointed at and rejects anything Telegram would refuse as a
 * photo. Shared by every image search tool, whichever provider produced the URL.
 */
class ImageDownloadClient(private val http: HttpClient) {

    private companion object {
        // image CDNs and wikis answer a default ktor user agent with 403, so a search could return
        // perfectly good URLs and still deliver nothing. mirrors FileDownloadClient's user agent.
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        const val MAX_DIMENSION = 10_000
        const val MAX_ASPECT_RATIO = 20.0

        val log = KotlinLogging.logger {}
    }

    /** Returns the bytes, or `null` when the response is not an image Telegram would show. */
    suspend fun download(url: String): ByteArray? {
        val response =
            http.get(url.withScheme()) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "image/*,*/*")
            }

        val bytes = response.bodyAsBytes()

        if (!looksLikeImage(bytes)) {
            log.info { "download: response is not an image, skipping contentType=[${response.contentType()}] url=[$url]" }
            return null
        }

        imageDimensions(bytes)?.let { (w, h) ->
            if (!isTelegramPhotoCompatible(w, h)) {
                log.info { "download: incompatible dimensions ${w}x$h, skipping url=[$url]" }
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
