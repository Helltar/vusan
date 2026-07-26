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
    val loadBytes: suspend () -> ByteArray
) {
    init {
        require(kind == AttachedFileKind.VIDEO || (durationSeconds == null && loadThumbnailBytes == null)) {
            "durationSeconds and loadThumbnailBytes belong to video attachments"
        }
    }
}
