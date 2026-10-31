package com.helltar.vusan.config

import com.helltar.vusan.llm.codex.defaultCodexAuthFile
import java.nio.file.Path

/**
 * Who renders a picture, and so who is billed. `IMAGE_PROVIDER` names it, every provider takes `IMAGE_MODEL`
 * and `IMAGE_QUALITY`, and each has its own way in: a key or the signed-in ChatGPT subscription. Without a
 * provider the drawing tools stay out.
 */
sealed interface ImageProviderConfig {
    val name: String
    val model: String
    val quality: String

    /** The OpenAI image API, billed per image against [apiKey]. */
    data class OpenAi(
        val apiKey: String,
        override val model: String = DEFAULT_MODEL,
        override val quality: String = DEFAULT_QUALITY,
    ) : ImageProviderConfig {

        override val name: String = "openai"

        init {
            require(apiKey.isNotBlank()) { "OPENAI_IMAGE_API_KEY must not be blank" }
            requireOpenAiModel(model, quality)
        }

        companion object {
            // shared with the codex backend, which takes the platform's current model too (measured on a plus
            // plan 2026-10-09) though the CLI still asks for gpt-image-2
            const val DEFAULT_MODEL = "gpt-image-2.5-flare"
            const val DEFAULT_QUALITY = "medium"

            // xhigh and max are gpt-image-2.5 only; older models top out at high and reject the rest, so this
            // is the union and the operator matches it to the model.
            val QUALITIES = setOf("low", "medium", "high", "xhigh", "max", "auto")
        }
    }

    /**
     * The Codex backend, metered against the ChatGPT subscription signed in at [authFile]. It renders on
     * OpenAI's models, so it takes [OpenAi]'s defaults and qualities.
     */
    data class Codex(
        val authFile: Path = defaultCodexAuthFile(),
        // the Codex CLI version claimed; `null` claims the newest of the floor, the CLI and the latest
        // release
        val clientVersion: String? = null,
        override val model: String = OpenAi.DEFAULT_MODEL,
        override val quality: String = OpenAi.DEFAULT_QUALITY,
    ) : ImageProviderConfig {

        override val name: String = "codex"

        init {
            requireOpenAiModel(model, quality)
        }
    }
}

private fun requireOpenAiModel(model: String, quality: String) {
    require(model.isNotBlank()) { "IMAGE_MODEL must not be blank" }
    require(quality in ImageProviderConfig.OpenAi.QUALITIES) {
        "IMAGE_QUALITY must be one of ${ImageProviderConfig.OpenAi.QUALITIES}"
    }
}
