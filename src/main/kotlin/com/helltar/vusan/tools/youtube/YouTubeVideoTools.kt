package com.helltar.vusan.tools.youtube

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.common.suspendToolGuard

@Suppress("unused")
class YouTubeVideoTools(private val client: YtDlpClient, private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(YouTubeVideoToolDescriptions.DOWNLOAD_VIDEO)
    suspend fun downloadVideo(
        @LLMDescription(YouTubeVideoToolDescriptions.DOWNLOAD_VIDEO_QUERY)
        query: String
    ): String = suspendToolGuard {
        when (val result = client.downloadVideo(query)) {
            is YtDlpResult.NotFound -> """No video found on YouTube for "$query"."""

            is YtDlpResult.TooLarge -> {
                val mb = result.sizeBytes / (1024 * 1024)
                """Video for "$query" is too large to send via Telegram even at the lowest quality (~${mb} MB, limit is 50 MB)."""
            }

            is YtDlpResult.AuthRequired -> "YouTube is asking yt-dlp to sign in. Configure `YT_DLP_COOKIES_FILE` in the bot environment."
            is YtDlpResult.Failure -> "Failed to fetch video: ${result.reason}"

            is YtDlpResult.Success -> {
                val video = result.value
                val filename = video.title.sanitizeFilename().ifBlank { "video" } + ".mp4"

                outbox.enqueue(
                    BotOutput.Video(
                        bytes = video.bytes,
                        filename = filename,
                        durationSeconds = video.durationSeconds,
                        width = video.width,
                        height = video.height,
                        sourceUrl = video.sourceUrl
                    )
                )

                "Video ready: ${video.title}"
            }
        }
    }
}
