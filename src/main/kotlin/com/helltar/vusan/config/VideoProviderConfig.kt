package com.helltar.vusan.config

/** Who makes videos when `VIDEO_PROVIDER` is set: the `generateVideo`, `editVideo` and `extendVideo` tools. */
sealed interface VideoProviderConfig {
    val name: String
    val model: String

    /**
     * xAI's Imagine API with a key from its console. On a team whose seat carries a Grok plan, the
     * videos are drawn from the plan's weekly included usage before any prepaid credit.
     *
     * [model] makes new videos; an edit or an extension always runs on the one model that takes them.
     * [voice] is a preset voice the bot speaks with when it films itself, `null` for the model's own.
     */
    data class Xai(
        val apiKey: String,
        override val model: String = DEFAULT_MODEL,
        val resolution: String = DEFAULT_RESOLUTION,
        val voice: String? = null,
    ) : VideoProviderConfig {

        override val name: String = "xai"

        init {
            require(apiKey.isNotBlank()) { "VIDEO_API_KEY must not be blank" }
            require(model.isNotBlank()) { "VIDEO_MODEL must not be blank" }
            require(resolution in RESOLUTIONS) { "VIDEO_RESOLUTION must be one of ${RESOLUTIONS.joinToString()}" }
            require(voice == null || voice.isNotBlank()) { "VIDEO_VOICE must not be blank" }
        }

        companion object {
            // the one that takes every input a video here can have: a first and a last frame, reference
            // images and a voice. the lite model is a quarter of the price and takes no references.
            const val DEFAULT_MODEL = "grok-imagine-video-1.5"
            const val DEFAULT_RESOLUTION = "720p"
            val RESOLUTIONS = setOf("480p", "720p", "1080p")
        }
    }
}
