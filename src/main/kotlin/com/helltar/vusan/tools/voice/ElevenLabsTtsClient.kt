package com.helltar.vusan.tools.voice

import com.helltar.vusan.config.ElevenLabsTtsConfig
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

private const val SYNTHESIS_TIMEOUT_MILLIS = 90_000L
private const val API_KEY_HEADER = "xi-api-key"

class ElevenLabsTtsClient(private val http: HttpClient, private val apiKey: String) {

    suspend fun synthesize(text: String, config: ElevenLabsTtsConfig): ByteArray {
        require(text.isNotBlank()) { "Text must not be blank" }

        val response: HttpResponse =
            // no output_format param: the API default (mp3_44100_128) is a format Telegram voice accepts
            http.post("https://api.elevenlabs.io/v1/text-to-speech/${config.voiceId}") {
                header(API_KEY_HEADER, apiKey)
                contentType(ContentType.Application.Json)
                accept(ContentType.parse("audio/mpeg"))

                timeout {
                    requestTimeoutMillis = SYNTHESIS_TIMEOUT_MILLIS
                    socketTimeoutMillis = SYNTHESIS_TIMEOUT_MILLIS
                }

                setBody(
                    ElevenLabsSpeechRequest(
                        text = text,
                        modelId = config.model
                    )
                )
            }

        val bytes = response.bodyAsBytes()
        check(bytes.isNotEmpty()) { "ElevenLabs TTS returned empty audio" }

        return bytes
    }
}
