package com.helltar.vusan.tools.vision

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.tools.suspendToolGuard

private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

@Suppress("unused")
class VisionTools(
    private val client: ImageVisionClient,
    private val attachedFile: AttachedFile?
) : ToolSet {

    @Tool
    @LLMDescription(VisionToolDescriptions.DESCRIBE_IMAGE)
    suspend fun describeImage(
        @LLMDescription(VisionToolDescriptions.FOCUS)
        focus: String = ""
    ): String = suspendToolGuard {
        val image = attachedFile ?: return@suspendToolGuard "No image is attached in this turn."

        if (!image.isImage)
            return@suspendToolGuard "The attached file `${image.name}` is not an image, so it can't be described visually."

        image.fileSizeBytes?.let {
            if (it > MAX_IMAGE_BYTES)
                return@suspendToolGuard "The image is too large for vision ($it bytes, limit $MAX_IMAGE_BYTES)."
        }

        val bytes = image.loadBytes()

        if (bytes.size > MAX_IMAGE_BYTES)
            return@suspendToolGuard "The image is too large for vision (${bytes.size} bytes, limit $MAX_IMAGE_BYTES)."

        client.describe(image, bytes, focus)
    }
}
