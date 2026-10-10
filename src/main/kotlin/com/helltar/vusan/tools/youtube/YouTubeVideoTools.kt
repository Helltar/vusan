package com.helltar.vusan.tools.youtube

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.keptNotSent
import com.helltar.vusan.tools.refusedByChat
import com.helltar.vusan.tools.suspendToolGuard

class YouTubeVideoTools(private val client: YtDlpClient, private val outbox: BotOutbox) : ToolSet {

    @Tool(YouTubeVideoToolDescriptions.DOWNLOAD_VIDEO)
    suspend fun downloadVideo(
        @Arg(YouTubeVideoToolDescriptions.DOWNLOAD_VIDEO_QUERY)
        query: String,
        @Arg(YouTubeVideoToolDescriptions.SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        if (send && !outbox.capabilities.videos)
            return@suspendToolGuard refusedByChat("videos")

        when (val result = client.downloadVideo(query)) {
            is YtDlpResult.NotFound -> """No video found on YouTube for "$query"."""

            is YtDlpResult.TooLarge -> {
                val mb = result.sizeBytes / (1024 * 1024)
                """Video for "$query" is too large to send to the chat even at the lowest quality (~${mb} MB, limit is ${YtDlpClient.VIDEO_MAX_FILE_SIZE_MB} MB)."""
            }

            is YtDlpResult.AuthRequired -> error("YouTube is asking yt-dlp to sign in. Configure `YT_DLP_COOKIES_FILE` in the bot environment.")
            is YtDlpResult.Failure -> error("Failed to fetch video: ${result.reason}")

            is YtDlpResult.Success -> {
                val video = result.value
                val filename = video.title.sanitizeFilename().ifBlank { "video" } + ".mp4"
                val label = keepOnShelf(filename, video.bytes)

                if (!send) return@suspendToolGuard keptNotSent("The video \"${video.title}\"", label)

                outbox.enqueue(
                    BotOutput.Video(
                        bytes = video.bytes,
                        filename = filename,
                        durationSeconds = video.durationSeconds,
                        width = video.width,
                        height = video.height,
                        thumbnail = video.thumbnailBytes,
                        sourceUrl = video.sourceUrl,
                    ),
                )

                "Video ready: ${video.title}"
            }
        }
    }
}
