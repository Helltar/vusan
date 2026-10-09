package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.ImageProviderConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.keptNotSent
import com.helltar.vusan.tools.refusedByChat
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

class ImageGenTools(
    private val client: OpenAiImageClient,
    private val config: ImageProviderConfig,
    private val outbox: BotOutbox,
    private val attachedFiles: List<AttachedFile> = emptyList(),
    private val selfImage: SelfImage? = null,
) : ToolSet {

    @Tool(ImageGenToolDescriptions.GENERATE_IMAGE)
    suspend fun generateImage(
        @Arg(ImageGenToolDescriptions.PROMPT)
        prompt: String,
        @Arg(ImageGenToolDescriptions.ORIENTATION)
        orientation: String = "square",
        @Arg(ImageGenToolDescriptions.SELF_PORTRAIT)
        selfPortrait: Boolean = false,
        @Arg(ImageGenToolDescriptions.SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        val trimmed = prompt.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Image prompt is empty — nothing to generate."

        if (trimmed.length > IMAGE_PROMPT_MAX_CHARS)
            return@suspendToolGuard "Image prompt is ${trimmed.length} characters, " +
                    "which exceeds the $IMAGE_PROMPT_MAX_CHARS-character limit. Shorten it and try again."

        if (send && !outbox.capabilities.photos)
            return@suspendToolGuard refusedByChat("photos")

        val size = orientation.toImageSize(config.model)
        val self = selfImage?.takeIf { selfPortrait }
        val reference = self?.reference

        val bytes =
            runCatching {
                if (reference == null)
                    client.generate(trimmed.withAppearance(self?.appearance), size, config)
                else
                    client.edit(selfPortraitPrompt(trimmed, self.appearance), listOf(reference), size, config)
            }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    if (e is ImageModerationBlocked) {
                        log.info { "Image generation blocked: stage=[${e.stage}] categories=[${e.categories.joinToString()}]" }
                        return@suspendToolGuard e.advice()
                    }

                    log.warn(e) {
                        "OpenAI image generation failed: model=${config.model} size=$size " +
                                "promptChars=${trimmed.length} selfPortrait=$selfPortrait"
                    }

                    return@suspendToolGuard "Image generation failed: ${e.message ?: e::class.simpleName}"
                }

        val name = imageFilename(bytes)
        val label = keepOnShelf(name, bytes)

        if (!send) return@suspendToolGuard keptNotSent("The image ($size, ${bytes.size} bytes)", label)

        outbox.enqueue(BotOutput.Photo(bytes = bytes, filename = name))

        "Image queued ($size, ${bytes.size} bytes). Do not add a separate user-facing confirmation."
    }

    @Tool(ImageGenToolDescriptions.EDIT_IMAGE)
    suspend fun editImage(
        @Arg(ImageGenToolDescriptions.EDIT_PROMPT)
        prompt: String,
        @Arg(ImageGenToolDescriptions.EDIT_ORIENTATION)
        orientation: String = "auto",
        @Arg(ImageGenToolDescriptions.EDIT_WITH_YOURSELF)
        withYourself: Boolean = false,
        @Arg(ImageGenToolDescriptions.EDIT_IMAGES)
        images: List<AttachedFile> = emptyList(),
        @Arg(ImageGenToolDescriptions.EDIT_SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        val trimmed = prompt.trim()
        val candidates = images.ifEmpty { attachedFiles }

        if (trimmed.isEmpty())
            return@suspendToolGuard "Edit instruction is empty — describe the change to make."

        if (trimmed.length > IMAGE_PROMPT_MAX_CHARS)
            return@suspendToolGuard "Edit instruction is ${trimmed.length} characters, " +
                    "which exceeds the $IMAGE_PROMPT_MAX_CHARS-character limit. Shorten it and try again."

        if (send && !outbox.capabilities.photos)
            return@suspendToolGuard refusedByChat("photos")

        val pictures = candidates.filter { it.kind == AttachedFileKind.IMAGE }

        if (pictures.isEmpty())
            return@suspendToolGuard candidates.firstOrNull()
                ?.let { "`${it.name}` is not an image, so it can't be edited." }
                ?: "No image is attached in this turn — ask the user to send or reply to one, or name one."

        val editable = pictures.mapNotNull { file -> file.editContentTypeOrNull()?.let { file to it } }

        if (editable.isEmpty())
            return@suspendToolGuard "`${pictures.first().name}` is not a supported image type for editing — " +
                    "use a PNG, JPEG, or WebP image."

        val self = selfImage?.reference?.takeIf { withYourself }
        val sources = mutableListOf<SourceImage>()

        // the first source is the one the model holds closest to the original, which is why a picture of
        // the bot puts its own face there and the user's images follow it.
        self?.let { sources += it }

        var uploadBytes = self?.bytes?.size ?: 0

        for ((file, contentType) in editable.take(MAX_EDIT_IMAGES - sources.size)) {
            file.fileSizeBytes?.let {
                if (it > MAX_EDIT_IMAGE_BYTES)
                    return@suspendToolGuard "`${file.name}` is too large to edit ($it bytes, limit $MAX_EDIT_IMAGE_BYTES)."
            }

            val bytes = file.loadBytes()

            if (bytes.size > MAX_EDIT_IMAGE_BYTES)
                return@suspendToolGuard "`${file.name}` is too large to edit (${bytes.size} bytes, limit $MAX_EDIT_IMAGE_BYTES)."

            uploadBytes += bytes.size

            if (uploadBytes > MAX_EDIT_TOTAL_BYTES)
                return@suspendToolGuard "The images add up to more than $MAX_EDIT_TOTAL_BYTES bytes — " +
                        "ask the user which of them the edit is about."

            sources += SourceImage(bytes, file.name, contentType)
        }

        val size = orientation.toImageSize(config.model)
        val instruction = self?.let { selfInEditPrompt(trimmed, selfImage?.appearance, sources.size - 1) } ?: trimmed

        val bytes =
            runCatching { client.edit(instruction, sources, size, config) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    if (e is ImageModerationBlocked) {
                        log.info { "Image edit blocked: stage=[${e.stage}] categories=[${e.categories.joinToString()}]" }
                        return@suspendToolGuard e.advice()
                    }

                    log.warn(e) {
                        "OpenAI image edit failed: model=${config.model} size=$size " +
                                "sources=${sources.size} promptChars=${trimmed.length}"
                    }

                    return@suspendToolGuard "Image edit failed: ${e.message ?: e::class.simpleName}"
                }

        val name = imageFilename(bytes)
        val label = keepOnShelf(name, bytes)

        if (!send) return@suspendToolGuard keptNotSent("The edited image ($size, ${bytes.size} bytes)", label)

        outbox.enqueue(BotOutput.Photo(bytes = bytes, filename = name))

        "Edited image queued (${sources.size} source image(s), $size, ${bytes.size} bytes). " +
                "Do not add a separate user-facing confirmation."
    }

    companion object {
        const val IMAGE_PROMPT_MAX_CHARS = 32_000
        const val MAX_EDIT_IMAGE_BYTES = 25 * 1024 * 1024

        // the edits endpoint takes up to 16 sources; a telegram album stops at ten long before that,
        // so this only bounds a turn that collected its images some other way.
        const val MAX_EDIT_IMAGES = 16

        // one upload, not one image: ten album photos at the per-image limit would be a quarter-gigabyte
        // request that spends its five-minute timeout uploading.
        const val MAX_EDIT_TOTAL_BYTES = 45 * 1024 * 1024

        private val log = KotlinLogging.logger {}
    }
}

/**
 * What to do next after the content filter refused, which is not the same on both sides of it: a
 * prompt it never drew is one the model has to change, while a picture it drew and then withheld is
 * often only a retry away.
 */
private fun ImageModerationBlocked.advice(): String {
    val flagged = categories.takeIf { it.isNotEmpty() }?.let { " (flagged: ${it.joinToString()})" }.orEmpty()

    return when (stage) {
        ImageModerationStage.INPUT ->
            "OpenAI's content filter rejected the description before anything was drawn$flagged. " +
                    "Do not send it again: offer the user a version without whatever crossed the line, " +
                    "or tell them this picture cannot be generated."

        ImageModerationStage.OUTPUT ->
            "OpenAI's content filter withheld the finished image$flagged. " +
                    "Try once more with a milder, more specific description; if that is withheld too, " +
                    "tell the user this picture cannot be generated."

        ImageModerationStage.UNKNOWN ->
            "OpenAI's content filter refused this image$flagged. " +
                    "Rewrite the description without the part that likely triggered it, " +
                    "or tell the user this picture cannot be generated."
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

/**
 * The requested framing as a size the configured model actually accepts.
 *
 * `gpt-image-2` and later take any `WIDTHxHEIGHT` within their bounds, which is what makes the 16:9
 * framings possible at all; the gpt-image-1 family takes three fixed sizes and rejects everything
 * else, so there they fall back to the nearest one rather than failing the request.
 */
private fun String.toImageSize(model: String): String {
    val flexible = supportsFlexibleSizes(model)

    return when (trim().lowercase()) {
        "portrait", "tall", "vertical" -> "1024x1536"
        "landscape", "horizontal" -> "1536x1024"
        "story", "phone" -> if (flexible) "1152x2048" else "1024x1536"
        "banner", "wide", "widescreen" -> if (flexible) "2048x1152" else "1536x1024"
        "auto" -> "auto"
        else -> "1024x1024"
    }
}

// telegram shows a photo whatever it is called, but the name follows the file when someone saves or
// forwards it, and the route decides the format: the platform asks for jpeg, codex sends what it likes.
private fun imageFilename(bytes: ByteArray): String =

    when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "image.jpg"

        else -> "image.png"
    }
