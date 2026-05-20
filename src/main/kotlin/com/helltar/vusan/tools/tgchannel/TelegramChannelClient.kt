package com.helltar.vusan.tools.tgchannel

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

class TelegramChannelClient(private val http: HttpClient) {

    suspend fun read(channel: String, maxPosts: Int = 12): TelegramChannelPage {
        val reference = TelegramChannelReference.parse(channel)
        val limit = maxPosts.coerceIn(1, 20)

        val response: HttpResponse =
            http.get(reference.webPreviewUrl) {
                header("User-Agent", "Mozilla/5.0 (compatible; VusanBot/1.0; +https://t.me)")
            }

        return TelegramChannelParser.parse(
            html = response.bodyAsText(),
            username = reference.username,
            url = reference.webPreviewUrl,
            maxPosts = limit
        )
    }

    suspend fun downloadImage(url: String): TelegramChannelImage {
        require(url.startsWith("http://") || url.startsWith("https://")) { "Image URL must be http(s)" }

        val response: HttpResponse = http.get(url)

        val contentType =
            response.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?: guessMimeType(url)

        check(contentType.startsWith("image/")) { "Telegram media is not an image ($contentType)" }

        return TelegramChannelImage(
            url = url,
            bytes = response.bodyAsBytes(),
            mimeType = contentType,
            filename = filenameFromUrl(url, contentType)
        )
    }

    private fun guessMimeType(url: String): String =
        when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

    private fun filenameFromUrl(url: String, mimeType: String): String {
        val extension =
            when (mimeType) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }

        val base =
            url.substringBefore('?')
                .substringAfterLast('/')
                .substringBeforeLast('.', missingDelimiterValue = "")
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
                .take(48)
                .takeIf { it.isNotBlank() } ?: "telegram-channel-image"

        return "$base.$extension"
    }
}
