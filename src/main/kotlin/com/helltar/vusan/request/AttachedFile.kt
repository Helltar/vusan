package com.helltar.vusan.request

import com.helltar.vusan.common.fileExtension

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
    // videos only: telegram's duration, which decides frame sampling, and its thumbnail, the one frame still
    // reachable past the bot download limit.
    val durationSeconds: Int? = null,
    val loadThumbnailBytes: (suspend () -> ByteArray)? = null,
    // a GIF stays kind VIDEO, being one for sampling and size limits; this marks where it is not: it has no
    // audio, and is usually a reaction rather than something to review.
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
 * The kind a file is handled as, from the platform's type first and its name otherwise, for files arriving
 * without one: a document, a tool's output, a sandbox file. A GIF is a video wherever it matters, marked by
 * [isAnimationFile].
 */
fun attachedFileKindOf(name: String, mimeType: String?): AttachedFileKind =
    when {
        isAnimationFile(name, mimeType) -> AttachedFileKind.VIDEO
        mimeType?.startsWith("image/") == true || name.fileExtension in IMAGE_EXTENSIONS -> AttachedFileKind.IMAGE
        mimeType?.startsWith("video/") == true || name.fileExtension in VIDEO_EXTENSIONS -> AttachedFileKind.VIDEO
        else -> AttachedFileKind.OTHER
    }

fun isAnimationFile(name: String, mimeType: String?): Boolean = mimeType == GIF_MIME_TYPE || name.fileExtension == GIF_EXTENSION

private const val GIF_EXTENSION = "gif"
private const val GIF_MIME_TYPE = "image/gif"
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "mpeg", "mpg", "3gp", "ogv")
