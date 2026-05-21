package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.OpenAiSttConfig
import com.helltar.vusan.stt.OpenAiWhisperClient
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.types.files.AudioFile
import dev.inmo.tgbotapi.types.files.TelegramMediaFile
import dev.inmo.tgbotapi.types.files.VoiceFile
import io.github.oshai.kotlinlogging.KotlinLogging

internal sealed interface VoiceTranscriptionResult {
    data class Success(val text: String) : VoiceTranscriptionResult
    data class TooLong(val durationSeconds: Long, val maxSeconds: Long) : VoiceTranscriptionResult
    data class Empty(val reason: String) : VoiceTranscriptionResult
    data class Failed(val cause: Throwable) : VoiceTranscriptionResult
}

internal data class AudioInput(
    val file: TelegramMediaFile,
    val durationSeconds: Long?,
    val mimeType: String?,
    val fileName: String
)

internal fun VoiceFile.toAudioInput(): AudioInput =
    AudioInput(
        file = this,
        durationSeconds = duration,
        mimeType = mimeType?.raw,
        fileName = "voice-${fileUniqueId.string}.${extensionFor(mimeType?.raw, default = "oga")}"
    )

internal fun AudioFile.toAudioInput(): AudioInput =
    AudioInput(
        file = this,
        durationSeconds = duration,
        mimeType = mimeType?.raw,
        fileName = fileName
            ?.takeIf { it.isNotBlank() }
            ?: "audio-${fileUniqueId.string}.${extensionFor(mimeType?.raw, default = "mp3")}"
    )

internal class VoiceTranscriber(
    private val whisper: OpenAiWhisperClient,
    private val config: OpenAiSttConfig
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    suspend fun transcribe(bot: TelegramBot, input: AudioInput): VoiceTranscriptionResult {
        val duration = input.durationSeconds
        if (duration != null && duration > config.maxDurationSeconds) {
            return VoiceTranscriptionResult.TooLong(duration, config.maxDurationSeconds)
        }

        val bytes =
            runCatching { bot.downloadFile(input.file) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()
                    log.warn(e) { "audio download failed: fileId=[${input.file.fileId.fileId}] size=[${input.file.fileSize?.bytes}]" }
                    return VoiceTranscriptionResult.Failed(e)
                }

        if (bytes.isEmpty()) return VoiceTranscriptionResult.Empty("downloaded audio file was empty")

        val transcript =
            runCatching { whisper.transcribe(bytes, input.fileName, input.mimeType) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()
                    log.warn(e) {
                        "whisper transcription failed: model=[${config.model}] " +
                                "fileBytes=[${bytes.size}] mime=[${input.mimeType}] name=[${input.fileName}]"
                    }
                    return VoiceTranscriptionResult.Failed(e)
                }

        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) return VoiceTranscriptionResult.Empty("provider returned empty transcript")

        return VoiceTranscriptionResult.Success(trimmed)
    }
}

private fun extensionFor(mimeType: String?, default: String): String =
    when (mimeType) {
        "audio/ogg", "audio/oga" -> "oga"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/webm" -> "webm"
        else -> default
    }

internal fun wrapVoiceTranscript(text: String): String =
    "<voice_transcript>\n${text.trim()}\n</voice_transcript>"
