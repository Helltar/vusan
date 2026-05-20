package com.helltar.vusan.tools.giphy

import kotlinx.serialization.Serializable

@Serializable
data class GiphySearchResponse(
    val data: List<GifData> = emptyList(),
    val meta: GiphyMeta
)

@Serializable
data class GifData(
    val id: String,
    val title: String,
    val images: GifImages
)

@Serializable
data class GifImages(
    val original: GifMedia
)

@Serializable
data class GifMedia(
    val mp4: String? = null,
    val url: String? = null
)

@Serializable
data class GiphyMeta(
    val status: Int,
    val msg: String = ""
)
