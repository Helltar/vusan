package com.helltar.vusan.stt

import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.config.OpenAiSttConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*

private const val TRANSCRIPTION_TIMEOUT_MILLIS = 120_000L

class OpenAiWhisperClient(private val http: HttpClient, private val config: OpenAiSttConfig) {

    /** [language] is an ISO-639-1 code the speech is in, when the caller knows it better than detection would. */
    suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String?, language: String? = null): String {
        require(audio.isNotEmpty()) { "Audio bytes must not be empty" }
        require(fileName.isNotBlank()) { "File name must not be blank" }

        val contentType =
            mimeType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Application.OctetStream

        val safeFileName = fileName.sanitizeFilename().ifBlank { "audio" }

        val response: WhisperTranscriptionResponse =
            http.submitFormWithBinaryData(
                url = "https://api.openai.com/v1/audio/transcriptions",
                formData = formData {
                    append("model", config.model)
                    append("response_format", "json")
                    language?.let { append("language", it) }
                    append(
                        key = "file",
                        value = audio,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, contentType.toString())
                            append(HttpHeaders.ContentDisposition, """filename="$safeFileName"""")
                        },
                    )
                },
            ) {
                bearerAuth(config.apiKey)
                timeout {
                    requestTimeoutMillis = TRANSCRIPTION_TIMEOUT_MILLIS
                    socketTimeoutMillis = TRANSCRIPTION_TIMEOUT_MILLIS
                }
            }.body()

        return response.text
    }
}
