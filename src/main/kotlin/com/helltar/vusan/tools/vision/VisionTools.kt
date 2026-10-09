package com.helltar.vusan.tools.vision

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

class VisionTools(
    private val client: ImageVisionClient,
    private val videoClient: VideoVisionClient,
    private val attachedFile: AttachedFile?,
) : ToolSet {

    @Tool(VisionToolDescriptions.DESCRIBE_IMAGE, readOnly = true, copiedToSandbox = true)
    suspend fun describeImage(
        @Arg(VisionToolDescriptions.FOCUS)
        focus: String = "",
        @Arg(VisionToolDescriptions.IMAGE_FILE)
        file: AttachedFile? = null,
    ): String = suspendToolGuard {
        val image = file ?: attachedFile ?: return@suspendToolGuard "No image is attached in this turn, and none was named."

        if (image.kind != AttachedFileKind.IMAGE)
            return@suspendToolGuard "`${image.name}` is not an image, so it can't be described visually."

        image.fileSizeBytes?.let {
            if (it > MAX_IMAGE_BYTES)
                return@suspendToolGuard "The image is too large for vision ($it bytes, limit $MAX_IMAGE_BYTES)."
        }

        val bytes = image.loadBytes()

        if (bytes.size > MAX_IMAGE_BYTES)
            return@suspendToolGuard "The image is too large for vision (${bytes.size} bytes, limit $MAX_IMAGE_BYTES)."

        client.describe(image, bytes, focus)
    }

    @Tool(VisionToolDescriptions.DESCRIBE_VIDEO, readOnly = true, copiedToSandbox = true)
    suspend fun describeVideo(
        @Arg(VisionToolDescriptions.VIDEO_FOCUS)
        focus: String = "",
        @Arg(VisionToolDescriptions.VIDEO_FILE)
        file: AttachedFile? = null,
    ): String = suspendToolGuard {
        val video = file ?: attachedFile ?: return@suspendToolGuard "No video is attached in this turn, and none was named."

        when (video.kind) {
            AttachedFileKind.VIDEO -> Unit

            AttachedFileKind.IMAGE ->
                return@suspendToolGuard "`${video.name}` is an image, not a video — use `describeImage` for it."

            AttachedFileKind.OTHER ->
                return@suspendToolGuard "`${video.name}` is not a video, so there is nothing to watch."
        }

        val oversize = video.fileSizeBytes != null && video.fileSizeBytes > MAX_VIDEO_BYTES

        if (!oversize) {
            downloadOrNull(video, video.loadBytes)
                ?.let { return@suspendToolGuard videoClient.describe(video, it, focus) }
        }

        // both an oversize video and a refused download leave telegram's own preview frame as the only
        // thing still reachable, which beats answering nothing about the video at all.
        val preview =
            video.loadThumbnailBytes?.let { downloadOrNull(video, it) }
                ?: return@suspendToolGuard "The video `${video.name}` could not be downloaded " +
                        "(Telegram serves bots files of at most ${MAX_VIDEO_BYTES / (1024 * 1024)} MB) " +
                        "and its preview frame is not available either."

        videoClient.describePreviewFrame(video, preview, focus)
    }

    private suspend fun downloadOrNull(video: AttachedFile, load: suspend () -> ByteArray): ByteArray? =
        runCatching { load() }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "video download failed: name=[${video.name}] sizeBytes=[${video.fileSizeBytes}]" }
            }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        // telegram serves bots files of at most 20 MB, so a bigger video cannot be fetched at all.
        const val MAX_VIDEO_BYTES = 20 * 1024 * 1024

        val log = KotlinLogging.logger {}
    }
}
