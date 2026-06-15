package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.config.OpenAiImageConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.util.*

private const val GENERATION_TIMEOUT_MILLIS = 180_000L

class OpenAiImageClient(private val http: HttpClient, private val apiKey: String) {

    suspend fun generate(prompt: String, size: String, config: OpenAiImageConfig): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        val response: OpenAiImageResponse =
            http.post("https://api.openai.com/v1/images/generations") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)

                timeout {
                    requestTimeoutMillis = GENERATION_TIMEOUT_MILLIS
                    socketTimeoutMillis = GENERATION_TIMEOUT_MILLIS
                }

                setBody(
                    OpenAiImageRequest(
                        model = config.model,
                        prompt = prompt,
                        size = size,
                        quality = config.quality
                    )
                )
            }.body()

        val encoded = response.data.firstOrNull()?.b64Json
        checkNotNull(encoded) { "OpenAI image response contained no image data" }

        val bytes = Base64.getDecoder().decode(encoded)
        check(bytes.isNotEmpty()) { "OpenAI image decoded to empty bytes" }

        return bytes
    }
}
