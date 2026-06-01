package com.helltar.vusan.tools.youtube

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class YtDlpInfo(
    val title: String? = null,
    val track: String? = null,
    val artist: String? = null,
    val uploader: String? = null,
    val channel: String? = null,
    val duration: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    @SerialName("filesize_approx") val filesizeApprox: Long? = null
)

@Serializable
internal data class YtDlpSearchResult(
    val entries: List<YtDlpSearchEntry>? = null
)

@Serializable
internal data class YtDlpSearchEntry(
    val id: String? = null,
    val url: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null
)

class YtDlpTrack(
    val bytes: ByteArray,
    val title: String,
    val performer: String,
    val durationSeconds: Int?,
    val sourceUrl: String?
)

class YtDlpVideo(
    val bytes: ByteArray,
    val title: String,
    val uploader: String?,
    val durationSeconds: Int?,
    val width: Int?,
    val height: Int?,
    val sourceUrl: String?
)

sealed class YtDlpResult<out T> {
    class Success<T>(val value: T) : YtDlpResult<T>()
    object NotFound : YtDlpResult<Nothing>()
    class TooLarge(val sizeBytes: Long) : YtDlpResult<Nothing>()
    object AuthRequired : YtDlpResult<Nothing>()
    class Failure(val reason: String) : YtDlpResult<Nothing>()
}
