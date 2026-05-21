package com.helltar.vusan.config

data class OpenAiSttConfig(
    val apiKey: String,
    val model: String,
    val maxDurationSeconds: Long
) {
    init {
        require(apiKey.isNotBlank()) { "OPENAI_STT_API_KEY must not be blank" }
        require(model.isNotBlank()) { "OPENAI_STT_MODEL must not be blank" }
        require(maxDurationSeconds > 0) { "OPENAI_STT_MAX_DURATION_SECONDS must be positive" }
    }

    companion object {
        const val DEFAULT_MODEL = "whisper-1"
        const val DEFAULT_MAX_DURATION_SECONDS = 300L
    }
}
