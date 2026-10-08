package com.helltar.vusan.tools.tgchannel

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.common.limitTo

private const val MAX_TELEGRAM_CHANNEL_IMAGE_BYTES = 8 * 1024 * 1024

class TelegramChannelImageDescriber(
    private val client: LlmClient,
    private val model: LlmModel,
) {

    suspend fun describe(image: TelegramChannelImage, post: TelegramChannelPost, focus: String): String {
        if (image.bytes.size > MAX_TELEGRAM_CHANNEL_IMAGE_BYTES) {
            return "Image is too large for vision (${image.bytes.size} bytes, limit $MAX_TELEGRAM_CHANNEL_IMAGE_BYTES)."
        }

        val description = client.complete(model, buildRequest(image, post, focus)).message.text.trim()

        return description.ifBlank { "Vision returned an empty description for this image." }
    }

    private fun buildRequest(image: TelegramChannelImage, post: TelegramChannelPost, focus: String) =
        ChatRequest(
            listOf(
                Message.System(
                    "You describe images embedded in public Telegram channel posts for a chat assistant. " +
                            "Be concise, factual, and avoid guessing identities. Mention visible text if any. " +
                            "Reply in the user's language when clear.",
                ),
                Message.User(
                    listOf(
                        Part.Text(
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
                        post.postedAt?.let { appendLine("- posted_at: $it") }
                        post.text.takeIf { it.isNotBlank() }?.let {
                            appendLine("- post_text:")
                            appendLine(it.limitTo(1_000))
                        }
                        appendLine()
                        appendLine("Image metadata:")
                        appendLine("- image_url: ${image.url}")
                        appendLine("- mime_type: ${image.mimeType}")
                        appendLine("- filename: ${image.filename}")
                    },
                        ),
                        Part.Image(image.bytes, image.mimeType, image.filename),
                    ),
                ),
            ),
        )
}
