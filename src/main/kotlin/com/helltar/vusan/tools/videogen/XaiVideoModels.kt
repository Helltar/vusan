package com.helltar.vusan.tools.videogen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An image or a video the request carries, as a data URL. */
@Serializable
internal data class XaiMedia(val url: String)

@Serializable
internal data class XaiVoice(@SerialName("voice_id") val voiceId: String)

@Serializable
internal data class XaiVideoGenerationRequest(
    val model: String,
    val prompt: String,
    val duration: Int,
    val resolution: String,
    @SerialName("aspect_ratio") val aspectRatio: String? = null,
    @SerialName("generate_audio") val generateAudio: Boolean? = null,
    val image: XaiMedia? = null,
    @SerialName("last_frame") val lastFrame: XaiMedia? = null,
    @SerialName("reference_images") val referenceImages: List<XaiMedia>? = null,
    @SerialName("reference_audios") val referenceAudios: List<XaiVoice>? = null,
)

@Serializable
internal data class XaiVideoEditRequest(
    val model: String,
    val prompt: String,
    val video: XaiMedia,
)

@Serializable
internal data class XaiVideoExtensionRequest(
    val model: String,
    val prompt: String,
    val video: XaiMedia,
    val duration: Int,
)

@Serializable
internal data class XaiVideoJob(@SerialName("request_id") val requestId: String)

@Serializable
internal data class XaiVideoStatus(
    val status: String,
    val progress: Int? = null,
    val video: XaiVideoOutput? = null,
    val error: XaiVideoError? = null,
)

@Serializable
internal data class XaiVideoOutput(
    val url: String? = null,
    val duration: Int? = null,
    @SerialName("respect_moderation") val respectModeration: Boolean = true,
)

@Serializable
internal data class XaiVideoError(val code: String, val message: String)
