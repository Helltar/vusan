package com.helltar.vusan.tools.imagegen

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.OpenAiImageConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress("unused")
class ImageGenTools(
    private val client: OpenAiImageClient,
    private val config: OpenAiImageConfig,
    private val outbox: BotOutbox,
    private val attachedFile: AttachedFile?,
    private val selfImage: SelfImage? = null
) : ToolSet {

    companion object {
        const val IMAGE_PROMPT_MAX_CHARS = 4_000
        const val MAX_EDIT_IMAGE_BYTES = 25 * 1024 * 1024
        private val log = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(ImageGenToolDescriptions.GENERATE_IMAGE)
    suspend fun generateImage(
        @LLMDescription(ImageGenToolDescriptions.PROMPT)
        prompt: String,
        @LLMDescription(ImageGenToolDescriptions.ORIENTATION)
        orientation: String = "square",
        @LLMDescription(ImageGenToolDescriptions.SELF_PORTRAIT)
        selfPortrait: Boolean = false
    ): String = suspendToolGuard {
        val trimmed = prompt.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Image prompt is empty — nothing to generate."

        if (trimmed.length > IMAGE_PROMPT_MAX_CHARS)
            return@suspendToolGuard "Image prompt is ${trimmed.length} characters, " +
                    "which exceeds the $IMAGE_PROMPT_MAX_CHARS-character limit. Shorten it and try again."

        val size = orientation.toImageSize()
        val self = selfImage?.takeIf { selfPortrait }
        val reference = self?.reference

        val bytes =
            runCatching {
                if (reference == null)
                    client.generate(trimmed.withAppearance(self?.appearance), size, config)
                else
                    client.edit(
                        selfPortraitPrompt(trimmed, self?.appearance),
                        reference.bytes, reference.filename, reference.contentType, size, config
                    )
            }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    log.warn(e) {
                        "OpenAI image generation failed: model=${config.model} size=$size " +
                                "promptChars=${trimmed.length} selfPortrait=$selfPortrait"
                    }

                    return@suspendToolGuard "Image generation failed: ${e.message ?: e::class.simpleName}"
                }

        outbox.enqueue(BotOutput.Photo(bytes = bytes, filename = "image.png"))

        "Image queued ($size, ${bytes.size} bytes). Do not add a separate user-facing confirmation."
    }

    @Tool
    @LLMDescription(ImageGenToolDescriptions.EDIT_IMAGE)
    suspend fun editImage(
        @LLMDescription(ImageGenToolDescriptions.EDIT_PROMPT)
        prompt: String,
        @LLMDescription(ImageGenToolDescriptions.EDIT_ORIENTATION)
        orientation: String = "auto"
    ): String = suspendToolGuard {
        val trimmed = prompt.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Edit instruction is empty — describe the change to make."

        if (trimmed.length > IMAGE_PROMPT_MAX_CHARS)
            return@suspendToolGuard "Edit instruction is ${trimmed.length} characters, " +
                    "which exceeds the $IMAGE_PROMPT_MAX_CHARS-character limit. Shorten it and try again."

        val image = attachedFile
            ?: return@suspendToolGuard "No image is attached in this turn — ask the user to send or reply to one."

        if (image.kind != AttachedFileKind.IMAGE)
            return@suspendToolGuard "The attached file `${image.name}` is not an image, so it can't be edited."

        val contentType = image.editContentTypeOrNull()
            ?: return@suspendToolGuard "`${image.name}` is not a supported image type for editing — use a PNG, JPEG, or WebP image."

        image.fileSizeBytes?.let {
            if (it > MAX_EDIT_IMAGE_BYTES)
                return@suspendToolGuard "The image is too large to edit ($it bytes, limit $MAX_EDIT_IMAGE_BYTES)."
        }

        val source = image.loadBytes()

        if (source.size > MAX_EDIT_IMAGE_BYTES)
            return@suspendToolGuard "The image is too large to edit (${source.size} bytes, limit $MAX_EDIT_IMAGE_BYTES)."

        val size = orientation.toImageSize()

        val bytes =
            runCatching { client.edit(trimmed, source, image.name, contentType, size, config) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    log.warn(e) {
                        "OpenAI image edit failed: model=${config.model} size=$size promptChars=${trimmed.length}"
                    }

                    return@suspendToolGuard "Image edit failed: ${e.message ?: e::class.simpleName}"
                }

        outbox.enqueue(BotOutput.Photo(bytes = bytes, filename = "image.png"))

        "Edited image queued ($size, ${bytes.size} bytes). Do not add a separate user-facing confirmation."
    }
}

private val SUPPORTED_EDIT_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")

private fun AttachedFile.editContentTypeOrNull(): String? {
    mimeType?.lowercase()?.let { if (it in SUPPORTED_EDIT_MIME_TYPES) return it }

    return imageContentTypeOrNull(name)
}

/** The image types both edit routes accept, read off a filename when nothing else declares one. */
internal fun imageContentTypeOrNull(filename: String): String? =
    when (filename.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
    }

private fun String.toImageSize(): String =
    when (trim().lowercase()) {
        "portrait", "tall", "vertical" -> "1024x1536"
        "landscape", "wide", "horizontal" -> "1536x1024"
        "auto" -> "auto"
        else -> "1024x1024"
    }
