package com.helltar.vusan.request

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
    val loadBytes: suspend () -> ByteArray
) {
    init {
        require(kind == AttachedFileKind.VIDEO || (durationSeconds == null && loadThumbnailBytes == null)) {
            "durationSeconds and loadThumbnailBytes belong to video attachments"
        }

        require(kind == AttachedFileKind.VIDEO || !isAnimation) { "isAnimation belongs to video attachments" }
    }
}
