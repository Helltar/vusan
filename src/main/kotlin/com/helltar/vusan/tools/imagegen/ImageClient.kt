package com.helltar.vusan.tools.imagegen

import kotlin.math.abs
import kotlin.math.ln

/**
 * Who draws a picture: one implementation per image provider, each with its own wire. A content-filter
 * refusal is thrown as [ImageModerationBlocked].
 */
interface ImageClient {

    /** The most source images one edit takes. */
    val maxEditImages: Int

    suspend fun generate(prompt: String, framing: ImageFraming): ByteArray

    /**
     * Edits [images] into one picture: retouching one, or combining several into a new scene. Every provider
     * holds the first image closest to the original, so a picture of the bot puts its reference photo there.
     */
    suspend fun edit(prompt: String, images: List<SourceImage>, framing: ImageFraming): ByteArray
}

/** The framing a picture is asked for; each provider turns it into its own size or aspect ratio. */
enum class ImageFraming {
    SQUARE,
    PORTRAIT,
    LANDSCAPE,
    STORY,
    BANNER,

    /** The provider's own choice; for an edit, close to the source. */
    AUTO;

    companion object {

        private val RATIOS =
            mapOf(
                SQUARE to 1.0,
                PORTRAIT to 2.0 / 3,
                LANDSCAPE to 3.0 / 2,
                STORY to 9.0 / 16,
                BANNER to 16.0 / 9,
            )

        /** The framing an orientation names, in any of the model's words for it; anything else is square. */
        fun of(orientation: String): ImageFraming =
            when (orientation.trim().lowercase()) {
                "portrait", "tall", "vertical" -> PORTRAIT
                "landscape", "horizontal" -> LANDSCAPE
                "story", "phone" -> STORY
                "banner", "wide", "widescreen" -> BANNER
                "auto" -> AUTO
                else -> SQUARE
            }

        /** The named framing nearest to [width] by [height]. */
        fun closestTo(width: Int, height: Int): ImageFraming {
            val ratio = ln(width.toDouble() / height)

            return RATIOS.entries.minBy { (_, r) -> abs(ln(r) - ratio) }.key
        }
    }
}

/** One image for an edit, with what every provider needs to name and type it. */
class SourceImage(val bytes: ByteArray, val filename: String, val contentType: String)

/** Which side of the filter refused: the prompt, or the already drawn picture. */
enum class ImageModerationStage { INPUT, OUTPUT, UNKNOWN }

/**
 * The provider's content filter refused the request, as opposed to the call failing: a filtered prompt must
 * be rewritten or given up on, while everything else is fixed by retrying.
 */
class ImageModerationBlocked(val stage: ImageModerationStage, val categories: List<String>) :
    IllegalStateException(
        "Image moderation blocked the request: stage=[${stage.name.lowercase()}] categories=[${categories.joinToString()}]",
    )
