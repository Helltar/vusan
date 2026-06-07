package com.helltar.vusan.tools.youtube

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.tools.common.suspendToolGuard

@Suppress("unused")
class YouTubeMusicTools(private val client: YtDlpClient, private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(YouTubeMusicToolDescriptions.PLAY_FULL_TRACK)
    suspend fun playFullTrack(
        @LLMDescription(YouTubeMusicToolDescriptions.PLAY_FULL_TRACK_QUERY)
        query: String
    ): String = suspendToolGuard {
        when (val result = client.downloadTrack(query)) {
            is YtDlpResult.NotFound -> """No track found on YouTube for "$query"."""

            is YtDlpResult.TooLarge -> {
                val mb = result.sizeBytes / (1024 * 1024)
                """Track for "$query" is too large to send via Telegram (~${mb} MB, limit is 45 MB)."""
            }

            is YtDlpResult.AuthRequired -> "YouTube is asking yt-dlp to sign in. Configure `YT_DLP_COOKIES_FILE` in the bot environment."
            is YtDlpResult.Failure -> "Failed to fetch track: ${result.reason}"

            is YtDlpResult.Success -> {
                val track = result.value
                val filename = "${track.performer} - ${track.title}".sanitizeFilename().ifBlank { "track" } + ".m4a"

                outbox.enqueue(
                    BotOutput.Audio(
                        bytes = track.bytes,
                        filename = filename,
                        title = track.title,
                        performer = track.performer,
                        durationSeconds = track.durationSeconds,
                        trackUrl = track.sourceUrl
                    )
                )

                "Track ready: ${track.title} by ${track.performer}"
            }
        }
    }
}
