package com.helltar.vusan.tools.tgchannel

import com.helltar.vusan.common.limitTo
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.tools.vision.ImageVisionClient

private const val MAX_TELEGRAM_CHANNEL_IMAGE_BYTES = 8 * 1024 * 1024
private const val MAX_POST_TEXT_CHARS = 1_000

// what a post's picture is looked at for, beyond whatever the person asked about
private const val POST_IMAGE_FOCUS =
    "This picture is part of a channel post: project visuals, UI, screenshots, visible text and quality signals matter."

/** A channel post's picture, looked at by the same vision as an attached image, with the post's text as its caption. */
class TelegramChannelImageDescriber(private val vision: ImageVisionClient) {

    suspend fun describe(image: TelegramChannelImage, post: TelegramChannelPost, focus: String): String {
        if (image.bytes.size > MAX_TELEGRAM_CHANNEL_IMAGE_BYTES) {
            return "Image is too large for vision (${image.bytes.size} bytes, limit $MAX_TELEGRAM_CHANNEL_IMAGE_BYTES)."
        }

        val file =
            AttachedFile(
                name = image.filename,
                fileSizeBytes = image.bytes.size.toLong(),
                mimeType = image.mimeType,
                kind = AttachedFileKind.IMAGE,
                caption = post.text.takeIf { it.isNotBlank() }?.limitTo(MAX_POST_TEXT_CHARS),
                loadBytes = { image.bytes },
            )

        return vision.describe(file, image.bytes, listOf(POST_IMAGE_FOCUS, focus.trim()).filter { it.isNotBlank() }.joinToString(" "))
    }
}
