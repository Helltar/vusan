package com.helltar.vusan.outbox

class RepliedPhoto(
    val fileId: String,
    val fileUniqueId: String?,
    val width: Int?,
    val height: Int?,
    val fileSizeBytes: ULong?,
    val caption: String?,
    val loadBytes: suspend () -> ByteArray
)
