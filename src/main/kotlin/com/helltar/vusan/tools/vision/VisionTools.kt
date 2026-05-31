package com.helltar.vusan.tools.vision

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.request.RepliedPhoto
import com.helltar.vusan.tools.common.suspendToolGuard

private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

@Suppress("unused")
class VisionTools(
    private val client: RepliedPhotoVisionClient,
    private val replyPhoto: RepliedPhoto?
) : ToolSet {

    @Tool
    @LLMDescription(VisionToolDescriptions.DESCRIBE_REPLIED_PHOTO)
    suspend fun describeRepliedPhoto(
        @LLMDescription(VisionToolDescriptions.FOCUS)
        focus: String = ""
    ): String = suspendToolGuard {
        val photo = replyPhoto ?: return@suspendToolGuard "No replied photo is available in this turn."

        photo.fileSizeBytes?.let {
            if (it > MAX_IMAGE_BYTES.toULong())
                return@suspendToolGuard "The replied photo is too large for vision (${it} bytes, limit $MAX_IMAGE_BYTES)."
        }

        val bytes = photo.loadBytes()

        if (bytes.size > MAX_IMAGE_BYTES)
            return@suspendToolGuard "The replied photo is too large for vision (${bytes.size} bytes, limit $MAX_IMAGE_BYTES)."

        client.describe(photo, bytes, focus)
    }
}
