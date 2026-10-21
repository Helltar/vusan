package com.helltar.vusan.tools.imagegen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A source image the request carries, as a data URL. */
@Serializable
internal data class XaiImageSource(val url: String)

@Serializable
internal data class XaiImageRequest(
    val model: String,
    val prompt: String,
    @SerialName("aspect_ratio") val aspectRatio: String?,
    val quality: String?,
    // the default is a temporary address to download from; base64 brings the picture in the same answer
    @SerialName("response_format") val responseFormat: String,
    val image: XaiImageSource? = null,
    val images: List<XaiImageSource>? = null,
)

@Serializable
internal data class XaiImageResponse(val data: List<XaiImageData> = emptyList())

@Serializable
internal data class XaiImageData(
    @SerialName("b64_json") val b64Json: String? = null,
    @SerialName("respect_moderation") val respectModeration: Boolean = true,
)
