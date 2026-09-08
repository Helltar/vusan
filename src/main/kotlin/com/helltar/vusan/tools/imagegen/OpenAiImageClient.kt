package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.CodexAuthStore
import com.helltar.vusan.config.OpenAiImageConfig
import com.helltar.vusan.config.codexImageHeaders
import com.helltar.vusan.infra.HttpStatusException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json

private const val PLATFORM_BASE_URL = "https://api.openai.com/v1/images"
private const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex/images"
private val IMAGE_TIMEOUT = 5.minutes

// telegram re-encodes every photo it delivers, so a lossless png is bytes nobody ever sees: asking for
// jpeg instead is a several-times smaller upload and keeps a max-quality picture under telegram's own
// size limit. the codex route has no such field and sends png.
private const val OUTPUT_FORMAT = "jpeg"
private const val OUTPUT_COMPRESSION = 95
private val errorJson = Json { ignoreUnknownKeys = true }

/** Which side of the filter refused: the prompt, or the picture the model had already drawn. */
enum class ImageModerationStage { INPUT, OUTPUT, UNKNOWN }

/**
 * OpenAI's content filter refused the request, rather than the call failing.
 *
 * Separate from a transport error because the two need opposite answers: a filtered prompt has to be
 * rewritten or given up on, while retrying is what fixes everything else.
 */
class ImageModerationBlocked(val stage: ImageModerationStage, val categories: List<String>) :
    IllegalStateException(
        "Image moderation blocked the request: stage=[${stage.name.lowercase()}] categories=[${categories.joinToString()}]"
    )

/** One image handed to the edit endpoint, with what either route needs to name and type it. */
class SourceImage(val bytes: ByteArray, val filename: String, val contentType: String)

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
            imageRequest {
                http.post("${baseUrl()}/generations") {
                    authorize()
                    contentType(ContentType.Application.Json)
                    imageTimeout()

                    setBody(
                        OpenAiImageRequest(
                            model = config.model,
                            prompt = prompt,
                            size = size,
                            quality = config.quality,
                            moderation = platformOnly(config.moderation),
                            outputFormat = platformOnly(OUTPUT_FORMAT),
                            outputCompression = platformOnly(OUTPUT_COMPRESSION)
                        )
                    )
                }.body()
            }

        return response.firstImageBytes()
    }

    /**
     * Edit [images] into one picture: retouching a single one, or combining several into a new scene.
     *
     * The first image is the one both routes hold closest to the original, so a picture of the bot puts
     * its own reference photo there.
     */
    suspend fun edit(
        prompt: String,
        images: List<SourceImage>,
        size: String,
        config: OpenAiImageConfig
    ): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        require(images.isNotEmpty()) { "At least one source image is required" }
        require(images.none { it.bytes.isEmpty() }) { "Image bytes must not be empty" }

        val response =
            imageRequest {
                when (auth) {
                    is ImageAuth.ApiKey -> editViaPlatform(prompt, images, size, config)
                    is ImageAuth.Codex -> editViaCodex(prompt, images, config)
                }
            }

        return response.firstImageBytes()
    }

    private suspend fun editViaPlatform(
        prompt: String,
        images: List<SourceImage>,
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
                append("moderation", config.moderation)
                append("output_format", OUTPUT_FORMAT)
                append("output_compression", OUTPUT_COMPRESSION.toString())

                if (supportsInputFidelity(config.model))
                    append("input_fidelity", "high")

                // the repeated `image[]` key is what makes several sources one edit rather than several
                images.forEach { image ->
                    append(
                        key = "image[]",
                        value = image.bytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, image.contentType)
                            append(HttpHeaders.ContentDisposition, """filename="${image.filename}"""")
                        }
                    )
                }
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
        images: List<SourceImage>,
        config: OpenAiImageConfig
    ): OpenAiImageResponse =
        http.post("$CODEX_BASE_URL/edits") {
            authorize()
            contentType(ContentType.Application.Json)
            imageTimeout()

            setBody(
                CodexImageEditRequest(
                    images = images.map { CodexImageSource(dataUrl(it.bytes, it.contentType)) },
                    prompt = prompt,
                    model = config.model,
                    quality = config.quality
                )
            )
        }.body()

    // a refusal from the content filter is a different answer than a failed call, so it is turned into
    // its own exception here, where the provider's error shape is known.
    private suspend fun <T> imageRequest(block: suspend () -> T): T =
        runCatching { block() }
            .getOrElse { e ->
                e.rethrowIfCancellation()
                throw (e as? HttpStatusException)?.moderationBlockOrNull() ?: e
            }

    // the codex backend's image request carries neither moderation nor output format — its own client
    // sends prompt, model, n, quality, size and background and nothing else — so these stay
    // platform-only rather than riding along in a request that may reject them.
    private fun <T> platformOnly(value: T): T? = value.takeIf { auth is ImageAuth.ApiKey }

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

/**
 * `input_fidelity` is what holds a face or a logo still through an edit, so a picture of the bot
 * keeps the face from its reference photo instead of drifting. Only the gpt-image-1 family takes it:
 * gpt-image-2 and later always edit at high fidelity and reject the parameter rather than ignore it.
 */
private fun supportsInputFidelity(model: String): Boolean = model.startsWith("gpt-image-1")

/**
 * Whether the model takes any `WIDTHxHEIGHT` within its bounds rather than three fixed sizes.
 *
 * The gpt-image-1 family takes `1024x1024`, `1536x1024`, `1024x1536` and `auto` and rejects the rest;
 * gpt-image-2 and later accept anything up to 3840px whose edges are multiples of 16.
 */
internal fun supportsFlexibleSizes(model: String): Boolean = !model.startsWith("gpt-image-1")

// both routes render on the same models, so a refusal arrives as OpenAI's error envelope. `code` is
// the documented signal; the safety wording is the fallback for a backend that forwards the message
// without the rest, which is all the codex route is known to promise.
private fun HttpStatusException.moderationBlockOrNull(): ImageModerationBlocked? {
    val error =
        body
            ?.let { runCatching { errorJson.decodeFromString<OpenAiImageErrorResponse>(it) }.getOrNull() }
            ?.error
            ?: return null

    val details = error.moderationDetails
    val message = error.message.orEmpty()

    val blocked =
        error.code == "moderation_blocked" ||
                details != null ||
                message.contains("safety system", ignoreCase = true) ||
                message.contains("content policy", ignoreCase = true)

    if (!blocked)
        return null

    val stage =
        when (details?.stage) {
            "input" -> ImageModerationStage.INPUT
            "output" -> ImageModerationStage.OUTPUT
            else -> ImageModerationStage.UNKNOWN
        }

    return ImageModerationBlocked(stage, details?.categories.orEmpty())
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
