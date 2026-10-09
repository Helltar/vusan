package com.helltar.vusan.tools.voice

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.ElevenLabsTtsConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.keptNotSent
import com.helltar.vusan.tools.refusedByChat
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

class VideoNoteTools(
    private val client: ElevenLabsTtsClient,
    private val config: ElevenLabsTtsConfig,
    private val portrait: ByteArray,
    private val outbox: BotOutbox,
    private val renderer: VideoNoteRenderer = FfmpegVideoNoteRenderer(),
) : ToolSet {

    @Tool(VideoNoteToolDescriptions.SPEAK_AS_VIDEO_NOTE)
    suspend fun speakAsVideoNote(
        @Arg(VideoNoteToolDescriptions.TEXT, takesReference = true)
        text: String,
        @Arg(VideoNoteToolDescriptions.SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        val trimmed = text.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Video note text is empty — nothing to speak."

        if (trimmed.length > VIDEO_NOTE_MAX_CHARS)
            return@suspendToolGuard "Video note text is ${trimmed.length} characters, " +
                    "which exceeds the $VIDEO_NOTE_MAX_CHARS-character limit of a round video. Shorten it and try again."

        if (send && !outbox.capabilities.videoNotes)
            return@suspendToolGuard refusedByChat("round video messages")

        val speech =
            runCatching { client.synthesize(trimmed, config) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    log.warn(e) {
                        "ElevenLabs TTS synthesize failed: model=${config.model} voiceId=${config.voiceId} " +
                                "textChars=${trimmed.length}"
                    }

                    return@suspendToolGuard "Video note synthesis failed: ${e.message ?: e::class.simpleName}"
                }

        // the speech is synthesized and paid for before ffmpeg is asked for anything, so a host without a
        // working ffmpeg still answers out loud instead of losing the turn's reply to a render failure.
        val video =
            renderer.render(portrait, speech)
                ?: run {
                    val label = keepOnShelf(VoiceTools.SPEECH_FILE_NAME, speech)

                    if (!send) return@suspendToolGuard keptNotSent("Rendering the round video failed, so only the speech", label)

                    if (!outbox.enqueue(BotOutput.Voice(speech)))
                        return@suspendToolGuard "Rendering the round video failed, and this chat does not accept voice messages " +
                                "either, so nothing was sent. " + keptNotSent("The speech", label)

                    return@suspendToolGuard "Rendering the round video failed; the same words are queued as a " +
                            "voice message instead. Do not add a separate user-facing confirmation."
                }

        val label = keepOnShelf(VIDEO_NOTE_FILE_NAME, video)

        if (!send) return@suspendToolGuard keptNotSent("The round video (${trimmed.length} chars, ${video.size} bytes)", label)

        outbox.enqueue(BotOutput.VideoNote(video, size = VIDEO_NOTE_SIZE))

        "Round video message queued (${trimmed.length} chars, ${video.size} bytes). " +
                "Do not add a separate user-facing confirmation."
    }

    companion object {
        // a round video lasts a minute at most and the renderer cuts it at 59 seconds, so the words have to
        // fit in that at the thousand characters a minute speech runs at, with room for a slower read
        const val VIDEO_NOTE_MAX_CHARS = 900

        private const val VIDEO_NOTE_FILE_NAME = "video-note.mp4"

        private val log = KotlinLogging.logger {}
    }
}
