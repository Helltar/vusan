package com.helltar.vusan.config

data class ElevenLabsTtsConfig(
    val model: String,
    val voiceId: String,
    val outputFormat: String
) {
    init {
        require(model.isNotBlank()) { "ElevenLabs TTS model must not be blank" }
        require(voiceId.isNotBlank()) { "ElevenLabs TTS voice ID must not be blank" }
        require(outputFormat.isNotBlank()) { "ElevenLabs TTS output format must not be blank" }
    }

    companion object {
        const val DEFAULT_MODEL = "eleven_v3"
        const val DEFAULT_OUTPUT_FORMAT = "mp3_44100_128"

        // https://elevenlabs.io/app/voice-library?voiceId=VD1if7jDVYtAKs4P0FIY
        const val DEFAULT_VOICE_ID = "VD1if7jDVYtAKs4P0FIY"
    }
}
