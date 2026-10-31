package com.helltar.vusan.config

/**
 * xAI's video API, on when `XAI_API_KEY` is set: the `generateVideo`, `editVideo` and `extendVideo` tools.
 * [model] makes new videos; edits and extensions always run on the one model that takes them. [voice] is a
 * preset voice for when the bot films itself, `null` for the model's own.
 */
data class XaiVideoConfig(
    val apiKey: String,
    val model: String = DEFAULT_MODEL,
    val resolution: String = DEFAULT_RESOLUTION,
    val voice: String? = null,
) {

    init {
        require(apiKey.isNotBlank()) { "XAI_API_KEY must not be blank" }
        require(model.isNotBlank()) { "XAI_VIDEO_MODEL must not be blank" }
        require(resolution in RESOLUTIONS) { "XAI_VIDEO_RESOLUTION must be one of ${RESOLUTIONS.joinToString()}" }
        require(voice == null || voice.isNotBlank()) { "XAI_VIDEO_VOICE must not be blank" }
    }

    companion object {
        // the one taking every input here: first and last frames, reference images and a voice. the lite
        // model costs a quarter and takes no references.
        const val DEFAULT_MODEL = "grok-imagine-video-1.5"
        const val DEFAULT_RESOLUTION = "720p"
        val RESOLUTIONS = setOf("480p", "720p", "1080p")
    }
}
