package com.helltar.vusan.tools.imagegen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiImageRequest(
    val model: String,
    val prompt: String,
    val size: String,
    val quality: String,
    val n: Int = 1
)

@Serializable
internal data class OpenAiImageResponse(
    val data: List<OpenAiImageData> = emptyList()
)

@Serializable
internal data class OpenAiImageData(
    @SerialName("b64_json")
    val b64Json: String? = null
)
