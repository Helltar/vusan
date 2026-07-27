package com.helltar.vusan.config

data class OpenAiVisionConfig(
    val apiKey: String,
    val model: String
) {
    init {
        require(apiKey.isNotBlank()) { "OPENAI_VISION_API_KEY must not be blank" }
        require(model.isNotBlank()) { "OPENAI_VISION_MODEL must not be blank" }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-5.4-mini"
    }
}
