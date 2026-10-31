package com.helltar.vusan.tools.vision

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.OpenAiSttConfig
import com.helltar.vusan.stt.OpenAiWhisperClient
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Turns a video's audio track into text. Every failure (a silent video, a provider error) is `null`, since
 * the frames alone still answer.
 */
interface VideoAudioTranscriber {

    /**
     * The most of a track that is heard, in seconds; the rest is cut before the provider, so never paid for.
     */
    val maxSeconds: Int

    suspend fun transcribeOrNull(audio: ByteArray): String?
}

class WhisperVideoAudioTranscriber(
    private val whisper: OpenAiWhisperClient,
    private val config: OpenAiSttConfig,
) : VideoAudioTranscriber {

    override val maxSeconds: Int = OpenAiSttConfig.MAX_DURATION_SECONDS.toInt()

    override suspend fun transcribeOrNull(audio: ByteArray): String? {
        if (audio.isEmpty()) return null

        return runCatching { whisper.transcribe(audio, AUDIO_FILE_NAME, AUDIO_MIME_TYPE) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "video audio transcription failed: model=[${config.model}] audioBytes=[${audio.size}]" }
            }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val AUDIO_FILE_NAME = "video-audio.m4a"
        const val AUDIO_MIME_TYPE = "audio/mp4"

        val log = KotlinLogging.logger {}
    }
}
