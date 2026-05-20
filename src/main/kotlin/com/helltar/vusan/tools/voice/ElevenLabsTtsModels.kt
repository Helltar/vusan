package com.helltar.vusan.tools.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
internal data class ElevenLabsSpeechRequest(
    val text: String,
    @SerialName("model_id")
    val modelId: String
)
