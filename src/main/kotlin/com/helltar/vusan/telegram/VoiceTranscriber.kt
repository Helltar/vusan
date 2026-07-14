package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.config.OpenAiSttConfig
import com.helltar.vusan.stt.OpenAiWhisperClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.objects.Audio
import org.telegram.telegrambots.meta.api.objects.Voice
import org.telegram.telegrambots.meta.generics.TelegramClient

internal sealed interface VoiceTranscriptionResult {
    data class Success(val text: String) : VoiceTranscriptionResult
    data class TooLong(val durationSeconds: Long, val maxSeconds: Long) : VoiceTranscriptionResult
    data class Empty(val reason: String) : VoiceTranscriptionResult
    data class Failed(val cause: Throwable) : VoiceTranscriptionResult
}

internal data class AudioInput(
    val fileId: String,
    val fileSizeBytes: Long?,
    val durationSeconds: Long?,
    val mimeType: String?,
    val fileName: String
)

internal fun Voice.toAudioInput(): AudioInput =
    AudioInput(
        fileId = fileId,
        fileSizeBytes = fileSize,
        durationSeconds = duration?.toLong(),
        mimeType = mimeType,
        fileName = "voice-$fileUniqueId.${extensionFor(mimeType, default = "ogg")}"
    )

internal fun Audio.toAudioInput(): AudioInput =
    AudioInput(
        fileId = fileId,
        fileSizeBytes = fileSize,
        durationSeconds = duration?.toLong(),
        mimeType = mimeType,
        fileName =
            fileName?.takeIf { it.isNotBlank() }
                ?: "audio-$fileUniqueId.${extensionFor(mimeType, default = "mp3")}"
    )

internal class VoiceTranscriber(private val whisper: OpenAiWhisperClient, private val config: OpenAiSttConfig) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    suspend fun transcribe(client: TelegramClient, input: AudioInput): VoiceTranscriptionResult {
        val duration = input.durationSeconds

        if (duration != null && duration > config.maxDurationSeconds) {
            return VoiceTranscriptionResult.TooLong(duration, config.maxDurationSeconds)
        }

        val bytes =
            runCatching { client.downloadFileBytes(input.fileId) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    log.warn(e) {
                        "audio download failed: fileId=[${input.fileId}] size=[${input.fileSizeBytes}]"
                    }

                    return VoiceTranscriptionResult.Failed(e)
                }

        if (bytes.isEmpty())
            return VoiceTranscriptionResult.Empty("downloaded audio file was empty")

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

        if (trimmed.isEmpty())
            return VoiceTranscriptionResult.Empty("provider returned empty transcript")

        return VoiceTranscriptionResult.Success(trimmed)
    }
}

private fun extensionFor(mimeType: String?, default: String): String =
    when (mimeType) {
        "audio/ogg", "audio/oga" -> "ogg"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/webm" -> "webm"
        else -> default
    }

internal fun wrapAudioTranscript(text: String): String =
    xmlBlock("audio_transcript", text)
