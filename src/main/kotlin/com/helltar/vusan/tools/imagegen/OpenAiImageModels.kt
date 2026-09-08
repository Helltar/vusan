package com.helltar.vusan.tools.imagegen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenAiImageRequest(
    val model: String,
    val prompt: String,
    val size: String,
    val quality: String,
    val moderation: String? = null,
    @SerialName("output_format")
    val outputFormat: String? = null,
    @SerialName("output_compression")
    val outputCompression: Int? = null,
    val n: Int = 1
)

@Serializable
internal data class CodexImageEditRequest(
    val images: List<CodexImageSource>,
    val prompt: String,
    val model: String,
    val quality: String,
    val n: Int = 1
)

@Serializable
internal data class CodexImageSource(
    @SerialName("image_url")
    val imageUrl: String
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

/** The error body a refused image request carries, cut down to what decides the answer. */
@Serializable
internal data class OpenAiImageErrorResponse(
    val error: OpenAiImageError? = null
)

@Serializable
internal data class OpenAiImageError(
    val message: String? = null,
    val code: String? = null,
    @SerialName("moderation_details")
    val moderationDetails: OpenAiModerationDetails? = null
)

@Serializable
internal data class OpenAiModerationDetails(
    @SerialName("moderation_stage")
    val stage: String? = null,
    val categories: List<String> = emptyList()
)
