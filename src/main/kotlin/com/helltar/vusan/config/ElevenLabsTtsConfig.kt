package com.helltar.vusan.config

data class ElevenLabsTtsConfig(
    val model: String,
    val voiceId: String
) {
    init {
        require(model.isNotBlank()) { "ElevenLabs TTS model must not be blank" }
        require(voiceId.isNotBlank()) { "ElevenLabs TTS voice ID must not be blank" }
    }

    companion object {
        const val DEFAULT_MODEL = "eleven_v3"

        // https://elevenlabs.io/app/voice-library?voiceId=VD1if7jDVYtAKs4P0FIY
        const val DEFAULT_VOICE_ID = "VD1if7jDVYtAKs4P0FIY"
    }
}
