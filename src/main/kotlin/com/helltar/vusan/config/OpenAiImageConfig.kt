package com.helltar.vusan.config

data class OpenAiImageConfig(
    val model: String,
    val quality: String
) {
    init {
        require(model.isNotBlank()) { "OPENAI_IMAGE_MODEL must not be blank" }
        require(quality in ALLOWED_QUALITIES) { "OPENAI_IMAGE_QUALITY must be one of $ALLOWED_QUALITIES" }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-image-1.5"
        const val DEFAULT_QUALITY = "medium"
        val ALLOWED_QUALITIES = setOf("low", "medium", "high", "auto")
    }
}
