package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.config.CodexAuthStore
import com.helltar.vusan.config.OpenAiImageConfig
import com.helltar.vusan.config.codexImageHeaders
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import java.util.*
import kotlin.time.Duration.Companion.minutes

private const val PLATFORM_BASE_URL = "https://api.openai.com/v1/images"
private const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex/images"
private val IMAGE_TIMEOUT = 5.minutes

/**
 * Where the image request goes and how it authenticates.
 *
 * The two routes are not interchangeable beyond the URL: the Platform edit endpoint takes a multipart
 * upload, while the Codex one takes JSON with the source image inlined as a data URL.
 */
sealed interface ImageAuth {

    data class ApiKey(val key: String) : ImageAuth {
        init {
            require(key.isNotBlank()) { "OPENAI_IMAGE_API_KEY must not be blank" }
        }
    }

    /** Reuses the ChatGPT session `codex login` wrote, so image generation needs no second key. */
    data class Codex(val store: CodexAuthStore) : ImageAuth
}

class OpenAiImageClient(private val http: HttpClient, private val auth: ImageAuth) {

    suspend fun generate(prompt: String, size: String, config: OpenAiImageConfig): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        val response: OpenAiImageResponse =
            http.post("${baseUrl()}/generations") {
                authorize()
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

        val response =
            when (auth) {
                is ImageAuth.ApiKey ->
                    editViaPlatform(prompt, imageBytes, imageFilename, imageContentType, size, config)

                is ImageAuth.Codex -> editViaCodex(prompt, imageBytes, imageContentType, config)
            }

        return response.firstImageBytes()
    }

    private suspend fun editViaPlatform(
        prompt: String,
        imageBytes: ByteArray,
        imageFilename: String,
        imageContentType: String,
        size: String,
        config: OpenAiImageConfig
    ): OpenAiImageResponse =
        http.submitFormWithBinaryData(
            url = "$PLATFORM_BASE_URL/edits",
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
            authorize()
            imageTimeout()
        }.body()

    // the codex edit endpoint takes JSON with the source inlined as a data URL rather than a multipart
    // upload, and infers the output size from that image — so no `size` is sent, and the caller must
    // read the size off the result instead of assuming the one it asked for.
    private suspend fun editViaCodex(
        prompt: String,
        imageBytes: ByteArray,
        imageContentType: String,
        config: OpenAiImageConfig
    ): OpenAiImageResponse =
        http.post("$CODEX_BASE_URL/edits") {
            authorize()
            contentType(ContentType.Application.Json)
            imageTimeout()

            setBody(
                CodexImageEditRequest(
                    images = listOf(CodexImageSource(dataUrl(imageBytes, imageContentType))),
                    prompt = prompt,
                    model = config.model,
                    quality = config.quality
                )
            )
        }.body()

    private fun baseUrl(): String =
        when (auth) {
            is ImageAuth.ApiKey -> PLATFORM_BASE_URL
            is ImageAuth.Codex -> CODEX_BASE_URL
        }

    private suspend fun HttpRequestBuilder.authorize() {
        when (auth) {
            is ImageAuth.ApiKey -> bearerAuth(auth.key)

            is ImageAuth.Codex ->
                codexImageHeaders(auth.store.credentials()).forEach { (name, value) -> header(name, value) }
        }
    }
}

private fun dataUrl(bytes: ByteArray, contentType: String): String =
    "data:$contentType;base64,${Base64.getEncoder().encodeToString(bytes)}"

private fun HttpRequestBuilder.imageTimeout() {
    timeout {
        requestTimeoutMillis = IMAGE_TIMEOUT.inWholeMilliseconds
        socketTimeoutMillis = IMAGE_TIMEOUT.inWholeMilliseconds
    }
}

private fun OpenAiImageResponse.firstImageBytes(): ByteArray {
    val encoded = data.firstOrNull()?.b64Json
    checkNotNull(encoded) { "OpenAI image response contained no image data" }

    val bytes = Base64.getDecoder().decode(encoded)
    check(bytes.isNotEmpty()) { "OpenAI image decoded to empty bytes" }

    return bytes
}
