package com.helltar.vusan.config

/** Which service actually renders the image, and therefore who pays for it. */
enum class ImageRoute {
    /** OpenAI Platform, billed per image against `OPENAI_IMAGE_API_KEY`. */
    PLATFORM,

    /** The Codex backend, metered against the signed-in ChatGPT subscription. */
    CODEX
}

/**
 * Which route image generation takes, or `null` when it is unavailable and the tools stay disabled.
 *
 * A paid image key wins whenever both are configured: it bills separately, while the Codex route
 * spends the same subscription quota the chat turns already run on.
 */
internal fun resolveImageRoute(hasImageApiKey: Boolean, provider: LlmProviderConfig): ImageRoute? =
    when {
        hasImageApiKey -> ImageRoute.PLATFORM
        provider is LlmProviderConfig.Codex -> ImageRoute.CODEX
        else -> null
    }

internal fun defaultImageModel(route: ImageRoute): String =
    when (route) {
        ImageRoute.PLATFORM -> OpenAiImageConfig.DEFAULT_MODEL
        ImageRoute.CODEX -> OpenAiImageConfig.DEFAULT_CODEX_MODEL
    }

data class OpenAiImageConfig(
    val model: String,
    val quality: String,
    val route: ImageRoute = ImageRoute.PLATFORM
) {
    init {
        require(model.isNotBlank()) { "OPENAI_IMAGE_MODEL must not be blank" }
        require(quality in ALLOWED_QUALITIES) { "OPENAI_IMAGE_QUALITY must be one of $ALLOWED_QUALITIES" }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-image-1.5"

        // the codex backend renders with gpt-image-2 and does not offer the platform catalog, so the
        // default has to follow the route rather than being one shared constant.
        const val DEFAULT_CODEX_MODEL = "gpt-image-2"

        const val DEFAULT_QUALITY = "medium"
        val ALLOWED_QUALITIES = setOf("low", "medium", "high", "auto")
    }
}
