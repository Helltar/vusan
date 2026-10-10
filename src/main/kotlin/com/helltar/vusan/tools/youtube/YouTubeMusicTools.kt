package com.helltar.vusan.tools.youtube

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.keptNotSent
import com.helltar.vusan.tools.refusedByChat
import com.helltar.vusan.tools.suspendToolGuard

class YouTubeMusicTools(private val client: YtDlpClient, private val outbox: BotOutbox) : ToolSet {

    @Tool(YouTubeMusicToolDescriptions.PLAY_FULL_TRACK)
    suspend fun playFullTrack(
        @Arg(YouTubeMusicToolDescriptions.PLAY_FULL_TRACK_QUERY)
        query: String,
        @Arg(YouTubeMusicToolDescriptions.SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        if (send && !outbox.capabilities.audios)
            return@suspendToolGuard refusedByChat("audio files")

        when (val result = client.downloadTrack(query)) {
            is YtDlpResult.NotFound -> """No track found on YouTube for "$query"."""

            is YtDlpResult.TooLarge -> {
                val mb = result.sizeBytes / (1024 * 1024)
                """Track for "$query" is too large to send to the chat (~${mb} MB, limit is ${YtDlpClient.AUDIO_MAX_FILE_SIZE_MB} MB)."""
            }

            is YtDlpResult.AuthRequired -> error("YouTube is asking yt-dlp to sign in. Configure `YT_DLP_COOKIES_FILE` in the bot environment.")
            is YtDlpResult.Failure -> error("Failed to fetch track: ${result.reason}")

            is YtDlpResult.Success -> {
                val track = result.value
                val filename = "${track.performer} - ${track.title}".sanitizeFilename().ifBlank { "track" } + ".m4a"
                val label = keepOnShelf(filename, track.bytes)

                if (!send) return@suspendToolGuard keptNotSent("The track \"${track.title}\" by ${track.performer}", label)

                outbox.enqueue(
                    BotOutput.Audio(
                        bytes = track.bytes,
                        filename = filename,
                        title = track.title,
                        performer = track.performer,
                        durationSeconds = track.durationSeconds,
                        trackUrl = track.sourceUrl,
                    ),
                )

                "Track ready: ${track.title} by ${track.performer}"
            }
        }
    }
}
