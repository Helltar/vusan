package com.helltar.vusan.request

class AttachedFile(
    val name: String,
    val fileSizeBytes: Long?,
    val mimeType: String?,
    val isImage: Boolean,
    val caption: String? = null,
    val loadBytes: suspend () -> ByteArray
)
