package com.helltar.vusan.tools.tavily

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

class TavilyClient(private val http: HttpClient, private val apiKey: String) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    suspend fun search(
        query: String,
        maxResults: Int = 5,
        includeImages: Boolean = false,
        topic: String? = null,
        timeRange: String? = null,
        excludeDomains: List<String> = emptyList()
    ): SearchResponse {
        require(query.isNotBlank()) { "Query must not be blank" }
        require(maxResults in 1..10) { "maxResults must be between 1 and 10" }

        return http.post("https://api.tavily.com/search") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                SearchRequest(
                    query = query,
                    maxResults = maxResults,
                    includeImages = includeImages,
                    includeImageDescriptions = includeImages,
                    topic = topic,
                    timeRange = timeRange,
                    excludeDomains = excludeDomains
                )
            )
        }.body()
    }

    suspend fun extract(url: String): ExtractResponse {
        require(url.isNotBlank()) { "URL must not be blank" }

        return http.post("https://api.tavily.com/extract") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(ExtractRequest(urls = listOf(url)))
        }.body()
    }

    suspend fun downloadImage(url: String): ByteArray? {
        val response: HttpResponse = http.get(url)
        val bytes = response.bodyAsBytes()

        if (!looksLikeImage(bytes)) {
            log.info { "downloadImage: response is not an image, skipping contentType=[${response.contentType()}] url=[$url]" }
            return null
        }

        imageDimensions(bytes)?.let { (w, h) ->
            if (!isTelegramPhotoCompatible(w, h)) {
                log.info { "downloadImage: incompatible dimensions ${w}x$h, skipping url=[$url]" }
                return null
            }
        }

        return bytes
    }

    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? =
        runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return@use null

                val reader = readers.next()

                try {
                    reader.input = stream
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()

    private fun isTelegramPhotoCompatible(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width > 10_000 || height > 10_000) return false

        val ratio = max(width, height).toDouble() / min(width, height)

        return ratio <= 20.0
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
