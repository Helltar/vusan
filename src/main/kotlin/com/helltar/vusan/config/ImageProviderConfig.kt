package com.helltar.vusan.config

import com.helltar.vusan.llm.codex.defaultCodexAuthFile
import java.nio.file.Path

/**
 * Who renders a picture, and therefore who is billed for it. `IMAGE_PROVIDER` names it, every provider
 * takes `IMAGE_MODEL` and `IMAGE_QUALITY`, and each brings its own way in: a key, or the signed-in
 * ChatGPT subscription. Nothing here switches itself on — without a provider the drawing tools stay out.
 */
sealed interface ImageProviderConfig {
    val name: String
    val model: String
    val quality: String

    /** The OpenAI image API, billed per image against [apiKey]. */
    data class OpenAi(
        val apiKey: String,
        override val model: String = ImageProviderConfig.DEFAULT_MODEL,
        override val quality: String = ImageProviderConfig.DEFAULT_QUALITY,
    ) : ImageProviderConfig {

        override val name: String = "openai"

        init {
            require(apiKey.isNotBlank()) { "IMAGE_API_KEY must not be blank" }
            requireSane(model, quality)
        }
    }

    /** The Codex backend, metered against the ChatGPT subscription signed in at [authFile]. */
    data class Codex(
        val authFile: Path = defaultCodexAuthFile(),
        // the Codex CLI version claimed to the backend; `null` leaves it to the installed CLI or this build's floor
        val clientVersion: String? = null,
        override val model: String = ImageProviderConfig.DEFAULT_MODEL,
        override val quality: String = ImageProviderConfig.DEFAULT_QUALITY,
    ) : ImageProviderConfig {

        override val name: String = "codex"

        init {
            requireSane(model, quality)
        }
    }

    companion object {
        // one default for both: the codex backend takes the platform's current model too, measured
        // live on a plus plan on 2026-10-09, even though the CLI itself still asks for gpt-image-2
        const val DEFAULT_MODEL = "gpt-image-2.5-flare"
        const val DEFAULT_QUALITY = "medium"

        // xhigh and max are gpt-image-2.5 only; older models top out at high and reject the rest,
        // so the set is the union and the operator matches it to the model they configured.
        val ALLOWED_QUALITIES = setOf("low", "medium", "high", "xhigh", "max", "auto")
    }
}

private fun requireSane(model: String, quality: String) {
    require(model.isNotBlank()) { "IMAGE_MODEL must not be blank" }
    require(quality in ImageProviderConfig.ALLOWED_QUALITIES) {
        "IMAGE_QUALITY must be one of ${ImageProviderConfig.ALLOWED_QUALITIES}"
    }
}
