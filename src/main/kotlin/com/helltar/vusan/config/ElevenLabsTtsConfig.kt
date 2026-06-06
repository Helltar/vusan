package com.helltar.vusan.config

data class ElevenLabsTtsConfig(
    val model: String,
    val voiceId: String,
    val outputFormat: String
) {
    companion object {
        const val DEFAULT_MODEL = "eleven_v3"
        const val DEFAULT_OUTPUT_FORMAT = "mp3_44100_128"

        // https://elevenlabs.io/app/voice-library?voiceId=VD1if7jDVYtAKs4P0FIY
        const val DEFAULT_VOICE_ID = "VD1if7jDVYtAKs4P0FIY"
    }
}
