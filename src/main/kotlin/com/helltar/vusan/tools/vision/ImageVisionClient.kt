package com.helltar.vusan.tools.vision

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import com.helltar.vusan.request.AttachedFile

class ImageVisionClient(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel
) {

    suspend fun describe(image: AttachedFile, bytes: ByteArray, focus: String): String {
        val description =
            promptExecutor.execute(buildPrompt(image, bytes, focus), model)
                .textContent()
                .trim()

        return description.ifBlank { "Vision returned an empty description for the image." }
    }

    private fun buildPrompt(image: AttachedFile, bytes: ByteArray, focus: String) =
        prompt("vusan-image-vision") {
            system(
                "You describe images for a chat assistant. " +
                        "Be concise, factual, and avoid guessing identities. " +
                        "Mention visible text if any. Reply in the user's language when clear."
            )
            user {
                text(
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
                    }
                )
                image(
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(bytes),
                        format = image.mimeType?.substringAfter('/', "jpeg") ?: "jpeg",
                        mimeType = image.mimeType ?: "image/jpeg",
                        fileName = image.name
                    )
                )
            }
        }
}
