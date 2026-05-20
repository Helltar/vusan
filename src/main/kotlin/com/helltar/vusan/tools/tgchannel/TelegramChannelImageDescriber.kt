package com.helltar.vusan.tools.tgchannel

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource

private const val MAX_TELEGRAM_CHANNEL_IMAGE_BYTES = 8 * 1024 * 1024

interface TelegramChannelImageDescriber {
    suspend fun describe(image: TelegramChannelImage, post: TelegramChannelPost, focus: String): String
}

class KoogTelegramChannelImageDescriber(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel
) : TelegramChannelImageDescriber {

    override suspend fun describe(image: TelegramChannelImage, post: TelegramChannelPost, focus: String): String {
        if (image.bytes.size > MAX_TELEGRAM_CHANNEL_IMAGE_BYTES) {
            return "Image is too large for vision (${image.bytes.size} bytes, limit $MAX_TELEGRAM_CHANNEL_IMAGE_BYTES)."
        }

        val description = promptExecutor
            .execute(buildPrompt(image, post, focus), model)
            .textContent()
            .trim()

        return description.ifBlank { "Vision returned an empty description for this image." }
    }

    private fun buildPrompt(image: TelegramChannelImage, post: TelegramChannelPost, focus: String) =
        prompt("vusan-telegram-channel-image-vision") {
            system(
                "You describe images embedded in public Telegram channel posts for a chat assistant. " +
                        "Be concise, factual, and avoid guessing identities. Mention visible text if any. " +
                        "Reply in the user's language when clear."
            )
            user {
                text(
                    buildString {
                        appendLine("Describe this Telegram channel post image for later summarization/evaluation.")
                        appendLine("Focus on project visuals, UI, screenshots, visible text, quality signals, and anything relevant to the user's request.")
                        appendLine("Keep it concise.")
                        if (focus.isNotBlank()) {
                            appendLine()
                            appendLine("User focus:")
                            appendLine(focus.trim())
                        }
                        appendLine()
                        appendLine("Post metadata:")
                        appendLine("- post_url: ${post.url}")
                        post.datetime?.let { appendLine("- datetime: $it") }
                        post.text.takeIf { it.isNotBlank() }?.let {
                            appendLine("- post_text:")
                            appendLine(it.take(1_000))
                        }
                        appendLine()
                        appendLine("Image metadata:")
                        appendLine("- image_url: ${image.url}")
                        appendLine("- mime_type: ${image.mimeType}")
                        appendLine("- filename: ${image.filename}")
                    }
                )
                image(
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(image.bytes),
                        format = image.mimeType.substringAfter('/', "jpeg"),
                        mimeType = image.mimeType,
                        fileName = image.filename
                    )
                )
            }
        }
}
