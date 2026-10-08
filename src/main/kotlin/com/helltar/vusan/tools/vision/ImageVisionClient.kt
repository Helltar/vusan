package com.helltar.vusan.tools.vision

import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.request.AttachedFile

/** What [ImageVisionClient.describe] answers when the model returns nothing at all. */
internal const val EMPTY_VISION_DESCRIPTION = "Vision returned an empty description for the image."

class ImageVisionClient(
    private val client: LlmClient,
    private val model: LlmModel,
) {

    suspend fun describe(image: AttachedFile, bytes: ByteArray, focus: String): String {
        val description = client.complete(model, buildRequest(image, bytes, focus)).message.text.trim()

        return description.ifBlank { EMPTY_VISION_DESCRIPTION }
    }

    private fun buildRequest(image: AttachedFile, bytes: ByteArray, focus: String) =
        ChatRequest(
            listOf(
                Message.System(
                    "You describe images for a chat assistant. " +
                            "Be concise, factual, and avoid guessing identities. " +
                            "Mention visible text if any. Reply in the user's language when clear.",
                ),
                Message.User(
                    listOf(
                        Part.Text(
                            buildString {
                        appendLine("Describe this image for answering the user's request.")
                        appendLine("Focus on visible objects, scene, people in general terms, UI/screenshots, visible text,")
                        appendLine("and details relevant to the request.")
                        appendLine("Keep it concise.")

                        if (focus.isNotBlank()) {
                            appendLine()
                            appendLine("User focus:")
                            appendLine(focus.trim())
                        }

                        image.caption?.takeIf { it.isNotBlank() }?.let {
                            appendLine()
                            appendLine("Caption:")
                            appendLine(it)
                        }
                    },
                        ),
                        Part.Image(bytes, image.mimeType ?: "image/jpeg", image.name),
                    ),
                ),
            ),
        )
}
