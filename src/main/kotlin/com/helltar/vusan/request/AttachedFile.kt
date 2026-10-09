package com.helltar.vusan.request

import java.net.URLConnection

enum class AttachedFileKind {
    IMAGE,
    VIDEO,
    OTHER
}

class AttachedFile(
    val name: String,
    val fileSizeBytes: Long?,
    val mimeType: String?,
    val kind: AttachedFileKind,
    val caption: String? = null,
    // videos only: telegram's own duration, which decides how frames are sampled, and its thumbnail —
    // the one frame still reachable when the video itself is over the bot download limit.
    val durationSeconds: Int? = null,
    val loadThumbnailBytes: (suspend () -> ByteArray)? = null,
    // a GIF is a video everywhere it matters (sampling, size limits), so it stays
    // kind VIDEO; this only marks the two places where it is not one — it carries no audio, and it is
    // usually thrown into a chat as a reaction rather than as something to review.
    val isAnimation: Boolean = false,
    val loadBytes: suspend () -> ByteArray,
) {
    init {
        require(kind == AttachedFileKind.VIDEO || (durationSeconds == null && loadThumbnailBytes == null)) {
            "durationSeconds and loadThumbnailBytes belong to video attachments"
        }

        require(kind == AttachedFileKind.VIDEO || !isAnimation) { "isAnimation belongs to video attachments" }
    }
}

/**
 * The kind a file is handled as, from what the platform says of it first and its name otherwise, wherever a
 * file arrives without one: a document, a file a tool made, a file read out of the sandbox. A GIF is a video
 * everywhere it matters, and [isAnimationFile] marks it.
 */
fun attachedFileKindOf(name: String, mimeType: String?): AttachedFileKind =
    when {
        isAnimationFile(name, mimeType) -> AttachedFileKind.VIDEO
        mimeType?.startsWith("image/") == true || name.extension in IMAGE_EXTENSIONS -> AttachedFileKind.IMAGE
        mimeType?.startsWith("video/") == true || name.extension in VIDEO_EXTENSIONS -> AttachedFileKind.VIDEO
        else -> AttachedFileKind.OTHER
    }

fun isAnimationFile(name: String, mimeType: String?): Boolean = mimeType == GIF_MIME_TYPE || name.extension == GIF_EXTENSION

/**
 * The media type a file's name says it has, for a file that arrives without one. The platform's own guess
 * misses some, webp among them, and vision would then label the bytes as a jpeg.
 */
fun mimeTypeOfName(name: String): String? = MIME_TYPES[name.extension] ?: URLConnection.guessContentTypeFromName(name)

private val String.extension: String
    get() = substringAfterLast('.', "").lowercase()

private const val GIF_EXTENSION = "gif"
private const val GIF_MIME_TYPE = "image/gif"
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "mpeg", "mpg", "3gp", "ogv")

private val MIME_TYPES =
    mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp", "gif" to "image/gif", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg",
        "wav" to "audio/wav", "flac" to "audio/flac", "aac" to "audio/aac",
    )
