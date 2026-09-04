package com.helltar.vusan.tools.voice

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.ElevenLabsTtsConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.suspendToolGuard
import com.helltar.vusan.tools.voice.VoiceTools.Companion.VOICE_TOOLS_MAX_CHARS
import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress("unused")
class VideoNoteTools(
    private val client: ElevenLabsTtsClient,
    private val config: ElevenLabsTtsConfig,
    private val portrait: ByteArray,
    private val outbox: BotOutbox,
    private val renderer: VideoNoteRenderer = FfmpegVideoNoteRenderer()
) : ToolSet {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(VideoNoteToolDescriptions.SPEAK_AS_VIDEO_NOTE)
    suspend fun speakAsVideoNote(
        @LLMDescription(VideoNoteToolDescriptions.TEXT)
        text: String
    ): String = suspendToolGuard {
        val trimmed = text.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Video note text is empty — nothing to speak."

        if (trimmed.length > VOICE_TOOLS_MAX_CHARS)
            return@suspendToolGuard "Video note text is ${trimmed.length} characters, " +
                    "which exceeds the $VOICE_TOOLS_MAX_CHARS-character limit. Shorten it and try again."

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
                    outbox.enqueue(BotOutput.Voice(speech))

                    return@suspendToolGuard "Rendering the round video failed; the same words are queued as a " +
                            "voice message instead. Do not add a separate user-facing confirmation."
                }

        outbox.enqueue(BotOutput.VideoNote(video, size = VIDEO_NOTE_SIZE))

        "Round video message queued (${trimmed.length} chars, ${video.size} bytes). " +
                "Do not add a separate user-facing confirmation."
    }
}
