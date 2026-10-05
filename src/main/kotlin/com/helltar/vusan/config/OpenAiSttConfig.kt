package com.helltar.vusan.config

data class OpenAiSttConfig(
    val apiKey: String,
    val model: String,
) {

    init {
        require(apiKey.isNotBlank()) { "OPENAI_STT_API_KEY must not be blank" }
        require(model.isNotBlank()) { "OPENAI_STT_MODEL must not be blank" }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-transcribe"

        // the longest voice message or video sound that is transcribed at all
        const val MAX_DURATION_SECONDS = 300L
    }
}
