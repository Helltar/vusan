package com.helltar.vusan.tools.imagegen

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.OpenAiImageConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress("unused")
class ImageGenTools(
    private val client: OpenAiImageClient,
    private val config: OpenAiImageConfig,
    private val outbox: BotOutbox
) : ToolSet {

    companion object {
        const val IMAGE_PROMPT_MAX_CHARS = 4_000
        private val log = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(ImageGenToolDescriptions.GENERATE_IMAGE)
    suspend fun generateImage(
        @LLMDescription(ImageGenToolDescriptions.PROMPT)
        prompt: String,
        @LLMDescription(ImageGenToolDescriptions.ORIENTATION)
        orientation: String = "square"
    ): String = suspendToolGuard {
        val trimmed = prompt.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Image prompt is empty — nothing to generate."

        if (trimmed.length > IMAGE_PROMPT_MAX_CHARS)
            return@suspendToolGuard "Image prompt is ${trimmed.length} characters, " +
                    "which exceeds the $IMAGE_PROMPT_MAX_CHARS-character limit. Shorten it and try again."

        val size = orientation.toImageSize()

        val bytes =
            runCatching { client.generate(trimmed, size, config) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    log.warn(e) {
                        "OpenAI image generation failed: model=${config.model} size=$size promptChars=${trimmed.length}"
                    }

                    return@suspendToolGuard "Image generation failed: ${e.message ?: e::class.simpleName}"
                }

        outbox.enqueue(BotOutput.Photo(bytes = bytes, filename = "image.png"))

        "Image queued ($size, ${bytes.size} bytes). Do not add a separate user-facing confirmation."
    }
}

private fun String.toImageSize(): String =
    when (trim().lowercase()) {
        "portrait", "tall", "vertical" -> "1024x1536"
        "landscape", "wide", "horizontal" -> "1536x1024"
        else -> "1024x1024"
    }
