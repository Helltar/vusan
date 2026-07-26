package com.helltar.vusan.tools.vision

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.OpenAiSttConfig
import com.helltar.vusan.stt.OpenAiWhisperClient
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Turns the audio track of a video into text. Every failure — a silent video, a length over the
 * speech-to-text budget, a provider error — is a `null`, because the sampled frames alone still
 * answer the request.
 */
fun interface VideoAudioTranscriber {

    suspend fun transcribeOrNull(audio: ByteArray, durationSeconds: Int?): String?
}

class WhisperVideoAudioTranscriber(
    private val whisper: OpenAiWhisperClient,
    private val config: OpenAiSttConfig
) : VideoAudioTranscriber {

    private companion object {
        const val AUDIO_FILE_NAME = "video-audio.m4a"
        const val AUDIO_MIME_TYPE = "audio/mp4"

        val log = KotlinLogging.logger {}
    }

    override suspend fun transcribeOrNull(audio: ByteArray, durationSeconds: Int?): String? {
        if (audio.isEmpty()) return null

        if (durationSeconds != null && durationSeconds > config.maxDurationSeconds) {
            log.info {
                "video audio left untranscribed: duration=[${durationSeconds}s] " +
                        "over OPENAI_STT_MAX_DURATION_SECONDS=[${config.maxDurationSeconds}]"
            }

            return null
        }

        return runCatching { whisper.transcribe(audio, AUDIO_FILE_NAME, AUDIO_MIME_TYPE) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "video audio transcription failed: model=[${config.model}] audioBytes=[${audio.size}]" }
            }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
