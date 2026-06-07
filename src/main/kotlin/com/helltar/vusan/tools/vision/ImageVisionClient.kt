package com.helltar.vusan.tools.vision

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import com.helltar.vusan.request.RepliedPhoto

class RepliedPhotoVisionClient(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel
) {

    suspend fun describe(photo: RepliedPhoto, bytes: ByteArray, focus: String): String {
        val description =
            promptExecutor.execute(buildPrompt(photo, bytes, focus), model)
                .textContent()
                .trim()

        return description.ifBlank { "Vision returned an empty description for the replied photo." }
    }

    private fun buildPrompt(photo: RepliedPhoto, bytes: ByteArray, focus: String) =
        prompt("vusan-replied-photo-vision") {
            system(
                "You describe Telegram reply photos for a chat assistant. " +
                        "Be concise, factual, and avoid guessing identities. " +
                        "Mention visible text if any. Reply in the user's language when clear."
            )
            user {
                text(
                    buildString {
                        appendLine("Describe this replied Telegram photo for answering the user's request.")
                        appendLine("Focus on visible objects, scene, people in general terms, UI/screenshots, visible text,")
                        appendLine("and details relevant to the request.")
                        appendLine("Keep it concise.")

                        if (focus.isNotBlank()) {
                            appendLine()
                            appendLine("User focus:")
                            appendLine(focus.trim())
                        }

                        photo.caption?.takeIf { it.isNotBlank() }?.let {
                            appendLine()
                            appendLine("Telegram caption:")
                            appendLine(it)
                        }

                        appendLine()
                        appendLine("Photo metadata:")
                        appendLine("- file_id: ${photo.fileId}")

                        photo.fileUniqueId?.let { appendLine("- file_unique_id: $it") }
                        photo.width?.let { appendLine("- width: $it") }
                        photo.height?.let { appendLine("- height: $it") }
                        photo.fileSizeBytes?.let { appendLine("- file_size_bytes: $it") }
                    }
                )
                image(
                    AttachmentSource.Image(
                        content = AttachmentContent.Binary.Bytes(bytes),
                        format = "jpg",
                        mimeType = "image/jpeg",
                        fileName = "telegram-reply-photo.jpg"
                    )
                )
            }
        }
}
