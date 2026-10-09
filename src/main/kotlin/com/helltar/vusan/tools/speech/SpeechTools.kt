package com.helltar.vusan.tools.speech

import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.config.OpenAiSttConfig
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.suspendToolGuard
import com.helltar.vusan.tools.vision.VideoSampler

/**
 * Speech-to-text on any recording the turn can name, not only the voice message a request arrives as.
 *
 * Everything goes through the [sampler] first — the ffmpeg pass the video tool uses, cut at the speech
 * budget — so a video, a voice note and an mp3 reach the provider as the same small m4a, and nothing longer
 * than a voice message may be is ever paid for.
 */
class SpeechTools(
    private val whisper: OpenAiWhisperClient,
    private val attachedFile: AttachedFile?,
    private val sampler: VideoSampler,
) : ToolSet {

    @Tool(SpeechToolDescriptions.TRANSCRIBE_AUDIO, readOnly = true, copiedToSandbox = true)
    suspend fun transcribeAudio(
        @Arg(SpeechToolDescriptions.FILE)
        file: AttachedFile? = null,
        @Arg(SpeechToolDescriptions.LANGUAGE)
        language: String = "",
    ): String = suspendToolGuard {
        val recording = file ?: attachedFile ?: return@suspendToolGuard "No recording is attached in this turn, and none was named."
        val code = language.trim().lowercase().takeIf { it.isNotEmpty() }

        require(code == null || LANGUAGE_CODE.matches(code)) { "`language` is a two-letter ISO-639-1 code such as `ky`, `uk` or `en`" }

        recording.fileSizeBytes?.let {
            if (it > MAX_INPUT_BYTES) return@suspendToolGuard "`${recording.name}` is too large to transcribe ($it bytes, limit $MAX_INPUT_BYTES)."
        }

        val audio =
            sampler.extractAudio(recording.loadBytes(), MAX_SECONDS)
                ?: return@suspendToolGuard "No sound could be read out of `${recording.name}`."

        val text = whisper.transcribe(audio, AUDIO_FILE_NAME, AUDIO_MIME_TYPE, code).trim()

        if (text.isEmpty())
            "Nothing in `${recording.name}` was recognized as speech."
        else
            "Use this transcript of `${recording.name}` (its first $MAX_SECONDS seconds at most):\n${xmlBlock("transcript", text)}"
    }

    private companion object {
        val LANGUAGE_CODE = Regex("[a-z]{2}")

        const val MAX_SECONDS = OpenAiSttConfig.MAX_DURATION_SECONDS.toInt()
        const val MAX_INPUT_BYTES = 50 * 1024 * 1024
        const val AUDIO_FILE_NAME = "audio.m4a"
        const val AUDIO_MIME_TYPE = "audio/mp4"
    }
}
