package com.helltar.vusan.common

import java.net.URLConnection

/** The extension of a file name, lowercased and without the dot; empty when it has none. */
val String.fileExtension: String
    get() = substringAfterLast('.', "").lowercase()

/**
 * The media type a file's name says it has, for a file that arrives without one. The platform's own
 * guess misses some, webp among them, and vision would then label the bytes as a jpeg.
 */
fun mimeTypeOfName(name: String): String? = MIME_BY_EXTENSION[name.fileExtension] ?: URLConnection.guessContentTypeFromName(name)

/** The extension a media type is usually written with, or `null` for one nothing here names. */
fun extensionOfMime(mimeType: String): String? = EXTENSION_BY_MIME[mimeType.lowercase()]

/** The image type the first bytes say they are — jpeg, png, gif, webp or bmp — or `null` for anything else. */
fun sniffedImageMimeType(bytes: ByteArray): String? {
    if (bytes.size < 12) return null

    fun b(i: Int) = bytes[i].toInt() and 0xFF

    return when {
        b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> "image/jpeg"
        b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "image/png"
        b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38 -> "image/gif"
        b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> "image/webp"
        b(0) == 0x42 && b(1) == 0x4D -> "image/bmp"
        else -> null
    }
}

private val MIME_BY_EXTENSION =
    mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
        "wav" to "audio/wav", "flac" to "audio/flac", "aac" to "audio/aac",
    )

// ktor's ContentType.fileExtensions() walks a reverse mime map and answers text/html with "acgi", so the
// common types are named by hand; a type missing here falls back to whatever the caller does without one
private val EXTENSION_BY_MIME =
    mapOf(
        "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/bmp" to "bmp",
        "image/svg+xml" to "svg",
        "video/mp4" to "mp4", "video/quicktime" to "mov", "video/webm" to "webm", "video/x-matroska" to "mkv",
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/flac" to "flac", "audio/aac" to "aac",
        "text/html" to "html", "text/plain" to "txt", "text/markdown" to "md", "text/javascript" to "js",
        "application/javascript" to "js", "application/x-tar" to "tar", "application/gzip" to "gz", "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
    )
