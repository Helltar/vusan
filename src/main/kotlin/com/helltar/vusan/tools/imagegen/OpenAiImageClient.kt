package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.config.llm.OPENAI_API_BASE_URL
import com.helltar.vusan.common.dataUrl
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.codex.CodexAuthStore
import com.helltar.vusan.llm.codex.codexRequestHeaders
import com.helltar.vusan.infra.HttpStatusException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.Json

private const val PLATFORM_BASE_URL = "$OPENAI_API_BASE_URL/v1/images"
private const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex/images"
private val IMAGE_TIMEOUT = 5.minutes

// telegram re-encodes every photo it delivers, so a lossless png is bytes nobody ever sees: asking for
// jpeg instead is a several-times smaller upload and keeps a max-quality picture under telegram's own
// size limit. the codex route has no such field and sends png.
// the filter cannot be turned off, only set to the less restrictive of its two strictnesses. the codex
// route takes no such field and filters the way chatgpt does.
private const val MODERATION = "low"
private const val OUTPUT_FORMAT = "jpeg"
private const val OUTPUT_COMPRESSION = 95
private val errorJson = Json { ignoreUnknownKeys = true }

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

class OpenAiImageClient(
    private val http: HttpClient,
    private val auth: ImageAuth,
    private val model: String,
    private val quality: String,
) : ImageClient {

    // the edits endpoint takes up to 16 sources; a telegram album stops at ten long before that,
    // so this only bounds a turn that collected its images some other way.
    override val maxEditImages = 16

    override suspend fun generate(prompt: String, framing: ImageFraming): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        val response: OpenAiImageResponse =
            imageRequest {
                http.post("${baseUrl()}/generations") {
                    authorize()
                    contentType(ContentType.Application.Json)
                    imageTimeout()

                    setBody(
                        OpenAiImageRequest(
                            model = model,
                            prompt = prompt,
                            size = framing.toSize(model),
                            quality = quality,
                            moderation = platformOnly(MODERATION),
                            outputFormat = platformOnly(OUTPUT_FORMAT),
                            outputCompression = platformOnly(OUTPUT_COMPRESSION),
                        ),
                    )
                }.observed().body()
            }

        return response.firstImageBytes()
    }

    override suspend fun edit(prompt: String, images: List<SourceImage>, framing: ImageFraming): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        require(images.isNotEmpty()) { "At least one source image is required" }
        require(images.none { it.bytes.isEmpty() }) { "Image bytes must not be empty" }

        val response =
            imageRequest {
                when (auth) {
                    is ImageAuth.ApiKey -> editViaPlatform(prompt, images, framing.toSize(model))
                    is ImageAuth.Codex -> editViaCodex(prompt, images)
                }
            }

        return response.firstImageBytes()
    }

    private suspend fun editViaPlatform(prompt: String, images: List<SourceImage>, size: String): OpenAiImageResponse =
        http.submitFormWithBinaryData(
            url = "$PLATFORM_BASE_URL/edits",
            formData = formData {
                append("model", model)
                append("prompt", prompt)
                append("size", size)
                append("quality", quality)
                append("moderation", MODERATION)
                append("output_format", OUTPUT_FORMAT)
                append("output_compression", OUTPUT_COMPRESSION.toString())

                if (supportsInputFidelity(model))
                    append("input_fidelity", "high")

                // the repeated `image[]` key is what makes several sources one edit rather than several
                images.forEach { image ->
                    append(
                        key = "image[]",
                        value = image.bytes,
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, image.contentType)
                            append(HttpHeaders.ContentDisposition, """filename="${image.filename}"""")
                        },
                    )
                }
            },
        ) {
            authorize()
            imageTimeout()
        }.body()

    // the codex edit endpoint takes JSON with the source inlined as a data URL rather than a multipart
    // upload, and infers the output size from that image — so no `size` is sent, and the caller must
    // read the size off the result instead of assuming the one it asked for.
    private suspend fun editViaCodex(prompt: String, images: List<SourceImage>): OpenAiImageResponse =
        http.post("$CODEX_BASE_URL/edits") {
            authorize()
            contentType(ContentType.Application.Json)
            imageTimeout()

            setBody(
                CodexImageEditRequest(
                    images = images.map { CodexImageSource(dataUrl(it.contentType, it.bytes)) },
                    prompt = prompt,
                    model = model,
                    quality = quality,
                ),
            )
        }.observed().body()

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

    // a picture drawn through the subscription draws on its windows as a turn does, and says so the same way
    private fun HttpResponse.observed(): HttpResponse =
        apply {
            (auth as? ImageAuth.Codex)?.store?.limits?.let { limits ->
                limits.observe { headers[it] }
                limits.countImage()
            }
        }

    private suspend fun HttpRequestBuilder.authorize() {
        when (auth) {
            is ImageAuth.ApiKey -> bearerAuth(auth.key)

            is ImageAuth.Codex ->
                codexRequestHeaders(auth.store.credentials()).forEach { (name, value) -> header(name, value) }
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
 * The framing as a size the configured model actually accepts.
 *
 * `gpt-image-2` and later take any `WIDTHxHEIGHT` within their bounds, which is what makes the 16:9
 * framings possible at all; the gpt-image-1 family takes three fixed sizes and rejects everything
 * else, so there they fall back to the nearest one rather than failing the request.
 */
private fun ImageFraming.toSize(model: String): String {
    val flexible = supportsFlexibleSizes(model)

    return when (this) {
        ImageFraming.SQUARE -> "1024x1024"
        ImageFraming.PORTRAIT -> "1024x1536"
        ImageFraming.LANDSCAPE -> "1536x1024"
        ImageFraming.STORY -> if (flexible) "1152x2048" else "1024x1536"
        ImageFraming.BANNER -> if (flexible) "2048x1152" else "1536x1024"
        ImageFraming.AUTO -> "auto"
    }
}

/**
 * Whether the model takes any `WIDTHxHEIGHT` within its bounds rather than three fixed sizes.
 *
 * The gpt-image-1 family takes `1024x1024`, `1536x1024`, `1024x1536` and `auto` and rejects the rest;
 * gpt-image-2 and later accept anything up to 3840px whose edges are multiples of 16.
 */
private fun supportsFlexibleSizes(model: String): Boolean = !model.startsWith("gpt-image-1")

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
