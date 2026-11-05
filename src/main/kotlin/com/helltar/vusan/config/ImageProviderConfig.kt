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

    /** Whether this provider and model draw an animated GIF. */
    val drawsGifs: Boolean

    /** The OpenAI image API, billed per image against [apiKey]. */
    data class OpenAi(
        val apiKey: String,
        override val model: String = DEFAULT_MODEL,
        override val quality: String = DEFAULT_QUALITY,
    ) : ImageProviderConfig {

        override val name: String = "openai"

        override val drawsGifs: Boolean
            get() = !NO_GIF_MODELS.matches(model)

        init {
            require(apiKey.isNotBlank()) { "OPENAI_IMAGE_API_KEY must not be blank" }
            requireOpenAiModel(model, quality)
        }

        companion object {
            // also the codex default, though that backend draws with its own current model whatever is named
            const val DEFAULT_MODEL = "gpt-image-2.6-flare"
            const val DEFAULT_QUALITY = "medium"

            // xhigh and max are gpt-image-2.5 and later; older models top out at high and reject the rest, so
            // this is the union and the operator matches it to the model.
            val QUALITIES = setOf("low", "medium", "high", "xhigh", "max", "auto")

            // the models before gpt-image-2.6 answer a gif request with a 400, so a newer one is assumed to draw
            // them. dated snapshots carry a `-…` suffix.
            private val NO_GIF_MODELS = Regex("""gpt-image-(1|1\.5|2|2\.5)(-.+)?""")
        }
    }

    /**
     * The Codex backend, metered against the ChatGPT subscription signed in at [authFile]. It renders on
     * OpenAI's models, so it takes [OpenAi]'s defaults and qualities, though it draws with its own current
     * model whatever [model] names.
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

        // the backend answers a gif request with "GIF output is not enabled for this request" (2026-11-05)
        override val drawsGifs: Boolean = false

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
