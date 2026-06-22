package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.config.OpenAiImageConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import java.util.*

private const val GENERATIONS_URL = "https://api.openai.com/v1/images/generations"
private const val EDITS_URL = "https://api.openai.com/v1/images/edits"
private const val IMAGE_TIMEOUT_MILLIS = 180_000L

class OpenAiImageClient(private val http: HttpClient, private val apiKey: String) {

    suspend fun generate(prompt: String, size: String, config: OpenAiImageConfig): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        val response: OpenAiImageResponse =
            http.post(GENERATIONS_URL) {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                imageTimeout()

                setBody(
                    OpenAiImageRequest(
                        model = config.model,
                        prompt = prompt,
                        size = size,
                        quality = config.quality
                    )
                )
            }.body()

        return response.firstImageBytes()
    }

    suspend fun edit(
        prompt: String,
        imageBytes: ByteArray,
        imageFilename: String,
        imageContentType: String,
        size: String,
        config: OpenAiImageConfig
    ): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        require(imageBytes.isNotEmpty()) { "Image bytes must not be empty" }

        val response: OpenAiImageResponse =
            http.submitFormWithBinaryData(
                url = EDITS_URL,
                formData = formData {
                    append("model", config.model)
                    append("prompt", prompt)
                    append("size", size)
                    append("quality", config.quality)
                    append(
                        key = "image[]",
                        value = imageBytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, imageContentType)
                            append(HttpHeaders.ContentDisposition, """filename="$imageFilename"""")
                        }
                    )
                }
            ) {
                bearerAuth(apiKey)
                imageTimeout()
            }.body()

        return response.firstImageBytes()
    }
}

private fun HttpRequestBuilder.imageTimeout() {
    timeout {
        requestTimeoutMillis = IMAGE_TIMEOUT_MILLIS
        socketTimeoutMillis = IMAGE_TIMEOUT_MILLIS
    }
}

private fun OpenAiImageResponse.firstImageBytes(): ByteArray {
    val encoded = data.firstOrNull()?.b64Json
    checkNotNull(encoded) { "OpenAI image response contained no image data" }

    val bytes = Base64.getDecoder().decode(encoded)
    check(bytes.isNotEmpty()) { "OpenAI image decoded to empty bytes" }

    return bytes
}
