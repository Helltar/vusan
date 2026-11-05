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

// the filter cannot be turned off, only set to the laxer of its two levels. the codex route has no such
// field and filters as chatgpt does.
private const val MODERATION = "low"

// telegram re-encodes every photo, so lossless png is wasted bytes: jpeg is a several-times smaller upload
// and keeps a max-quality picture under telegram's size limit. the codex route has no such field and
// sends png.
private const val OUTPUT_FORMAT = "jpeg"
private const val OUTPUT_COMPRESSION = 95
private const val GIF_FORMAT = "gif"

// what the codex cli sends; the plan sizes from the prompt whatever is here
private const val CODEX_SIZE = "auto"

// a gif's frames are drawn as one sheet within the still picture's pixel budget, so an explicit frame size
// fails for most frame counts (9 frames at 1024x1024 asks for a 3072x3072 sheet); `auto` fits the sheet,
// at 512x512 frames as measured.
private const val GIF_SIZE = "auto"
private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Where the image request goes and how it authenticates. The routes differ beyond the URL: the Platform edit
 * endpoint takes a multipart upload, the Codex one JSON with the source inlined as a data URL.
 */
sealed interface ImageAuth {

    data class ApiKey(val key: String) : ImageAuth {
        init {
            require(key.isNotBlank()) { "OPENAI_IMAGE_API_KEY must not be blank" }
        }
    }

    /** Reuses the ChatGPT session `codex login` wrote, so images need no second key. */
    data class Codex(val store: CodexAuthStore) : ImageAuth
}

class OpenAiImageClient(
    private val http: HttpClient,
    private val auth: ImageAuth,
    private val model: String,
    private val quality: String,
) : ImageClient {

    // the edits endpoint takes up to 16 sources; a telegram album stops at ten, so this only bounds images
    // collected otherwise.
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
                            prompt = framedForCodex(prompt, framing),
                            size = if (auth is ImageAuth.Codex) CODEX_SIZE else framing.toSize(model),
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
                    is ImageAuth.ApiKey -> editViaPlatform(prompt, images, framing.toSize(model), stillOutput())
                    is ImageAuth.Codex -> editViaCodex(framedForCodex(prompt, framing), images)
                }
            }

        return response.firstImageBytes()
    }

    // the gif route is the platform's: the codex backend answers "GIF output is not enabled for this request"
    override suspend fun animate(prompt: String, images: List<SourceImage>, animation: ImageAnimation): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        require(images.none { it.bytes.isEmpty() }) { "Image bytes must not be empty" }
        check(auth is ImageAuth.ApiKey) { "GIFs are drawn on the platform route only" }

        val output = gifOutput(animation)

        val response: OpenAiImageResponse =
            imageRequest {
                if (images.isNotEmpty())
                    editViaPlatform(prompt, images, GIF_SIZE, output)
                else
                    http.post("$PLATFORM_BASE_URL/generations") {
                        authorize()
                        contentType(ContentType.Application.Json)
                        imageTimeout()

                        setBody(
                            OpenAiImageRequest(
                                model = model,
                                prompt = prompt,
                                size = GIF_SIZE,
                                quality = quality,
                                moderation = MODERATION,
                                outputFormat = GIF_FORMAT,
                                frameCount = animation.frames,
                                frameDurationMs = animation.frameDurationMs,
                            ),
                        )
                    }.body()
            }

        return response.firstImageBytes()
    }

    private suspend fun editViaPlatform(
        prompt: String,
        images: List<SourceImage>,
        size: String,
        output: List<Pair<String, String>>,
    ): OpenAiImageResponse =
        http.submitFormWithBinaryData(
            url = "$PLATFORM_BASE_URL/edits",
            formData = formData {
                append("model", model)
                append("prompt", prompt)
                append("size", size)
                append("quality", quality)
                append("moderation", MODERATION)
                output.forEach { (key, value) -> append(key, value) }

                if (supportsInputFidelity(model))
                    append("input_fidelity", "high")

                // the repeated `image[]` key makes several sources one edit
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

    // JSON with the source inlined instead of multipart, and the output size inferred from that image: no
    // `size` is sent, and the caller reads the size off the result.
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

    // the plan picks the size from the prompt alone and drops the `size` field, so the framing is written into
    // the text, which it does follow (square, 16:9 and 9:16 came back as asked, 2026-11-05)
    private fun framedForCodex(prompt: String, framing: ImageFraming): String =
        framing.formatLine()?.takeIf { auth is ImageAuth.Codex }?.let { "$prompt\n\n$it" } ?: prompt

    // a content-filter refusal is a different answer from a failed call, so it becomes its own exception
    // here, where the error shape is known.
    private suspend fun <T> imageRequest(block: suspend () -> T): T =
        runCatching { block() }
            .getOrElse { e ->
                e.rethrowIfCancellation()
                throw (e as? HttpStatusException)?.moderationBlockOrNull() ?: e
            }

    // the codex image request carries neither moderation nor output format (its own client sends prompt,
    // model, n, quality, size and background only), so these stay platform-only.
    private fun <T> platformOnly(value: T): T? = value.takeIf { auth is ImageAuth.ApiKey }

    private fun baseUrl(): String =

        when (auth) {
            is ImageAuth.ApiKey -> PLATFORM_BASE_URL
            is ImageAuth.Codex -> CODEX_BASE_URL
        }

    // a picture drawn on the subscription spends its windows as a turn does, and reports them the same way
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

private fun stillOutput(): List<Pair<String, String>> =
    listOf("output_format" to OUTPUT_FORMAT, "output_compression" to OUTPUT_COMPRESSION.toString())

// the edits endpoint takes a gif too, though the guide says it does not (measured 2026-11-05)
private fun gifOutput(animation: ImageAnimation): List<Pair<String, String>> =
    listOf(
        "output_format" to GIF_FORMAT,
        "frame_count" to animation.frames.toString(),
        "frame_duration_ms" to animation.frameDurationMs.toString(),
    )

/**
 * `input_fidelity` holds a face or logo still through an edit, so the bot keeps its reference face. Only the
 * gpt-image-1 family takes it: gpt-image-2 and later always edit at high fidelity and reject the parameter.
 */
private fun supportsInputFidelity(model: String): Boolean = model.startsWith("gpt-image-1")

/**
 * The framing as a size the model accepts. `gpt-image-2` and later take any `WIDTHxHEIGHT` within bounds,
 * which makes 16:9 possible; the gpt-image-1 family takes three fixed sizes, so it falls back to the nearest
 * one.
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

/** The framing in words, for a backend that reads the shape off the prompt; `null` leaves it to the source. */
private fun ImageFraming.formatLine(): String? =
    when (this) {
        ImageFraming.SQUARE -> "Output format: square image, 1:1 aspect ratio."
        ImageFraming.PORTRAIT -> "Output format: portrait image, 2:3 aspect ratio."
        ImageFraming.LANDSCAPE -> "Output format: landscape image, 3:2 aspect ratio."
        ImageFraming.STORY -> "Output format: portrait image, 9:16 aspect ratio."
        ImageFraming.BANNER -> "Output format: landscape image, 16:9 aspect ratio."
        ImageFraming.AUTO -> null
    }

/**
 * Whether the model takes any `WIDTHxHEIGHT` (up to 3840px, edges multiples of 16, gpt-image-2 and later)
 * rather than gpt-image-1's `1024x1024`, `1536x1024`, `1024x1536` and `auto`.
 */
private fun supportsFlexibleSizes(model: String): Boolean = !model.startsWith("gpt-image-1")

// both routes use the same models, so a refusal is OpenAI's error envelope. `code` is the documented signal;
// the safety wording is the fallback for a backend forwarding only the message, which is all the codex route
// promises.
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
