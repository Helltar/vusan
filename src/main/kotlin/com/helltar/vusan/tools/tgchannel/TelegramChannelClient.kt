package com.helltar.vusan.tools.tgchannel

import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.files.FileDownloadResult

class TelegramChannelClient(private val downloader: FileDownloadClient) {
    private companion object {
        const val PAGE_LIMIT = 4 * 1024 * 1024L
        const val IMAGE_LIMIT = 10 * 1024 * 1024L
    }

    internal suspend fun read(
        reference: TelegramChannelReference,
        before: Long? = null,
        query: String = "",
        maxPosts: Int = POSTS_PER_PAGE
    ): TelegramChannelPage {
        val url = reference.webPreviewUrl(before, query)
        val response = downloader.download(url, maxBytes = PAGE_LIMIT)
        check(response is FileDownloadResult.Success) { "Telegram preview exceeds the download limit" }

        // a username that is not a channel with a public preview (a bot, a user, a group, a channel
        // that turned the preview off, or nothing at all) redirects to the plain t.me/<name> page.
        val previewAvailable = response.url.encodedPath.startsWith("/s/")

        return TelegramChannelParser
            .parse(
                html = response.bytes.toString(Charsets.UTF_8),
                username = reference.username,
                url = url,
                maxPosts = maxPosts
            )
            .copy(previewAvailable = previewAvailable)
    }

    suspend fun downloadImage(url: String): TelegramChannelImage {
        require(url.startsWith("http://") || url.startsWith("https://")) { "Image URL must be http(s)" }

        val response = downloader.download(url, maxBytes = IMAGE_LIMIT)
        check(response is FileDownloadResult.Success) { "Telegram image exceeds the download limit" }

        val contentType =
            response.contentType?.lowercase()
                ?: guessMimeType(url)

        check(contentType.startsWith("image/")) { "Telegram media is not an image ($contentType)" }

        return TelegramChannelImage(
            url = url,
            bytes = response.bytes,
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
