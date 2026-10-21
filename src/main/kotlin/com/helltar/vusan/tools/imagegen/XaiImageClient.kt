package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.ImageProviderConfig
import com.helltar.vusan.infra.HttpStatusException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.*
import kotlin.time.Duration.Companion.minutes

private const val XAI_IMAGES_URL = "https://api.x.ai/v1/images"

// a new picture at medium quality took 80 to 90 seconds when measured, so this leaves room for a slow one
private val IMAGE_TIMEOUT = 5.minutes

// what a 400 says when moderation rejected a video, measured on 2026-10-21; an image is assumed to say the same
private const val MODERATED_CODE = "imagine:content-moderated"

/**
 * xAI's Imagine image API: JSON both ways, the sources inline as data URLs and the picture back as
 * base64 in the same answer.
 *
 * No resolution is asked for, which is 1k: Telegram shows a photo at most 1280 pixels on its long side,
 * and 2k comes back as a PNG of seven megabytes for the same picture.
 */
class XaiImageClient(private val http: HttpClient, private val config: ImageProviderConfig.Xai) : ImageClient {

    // documented for grok-imagine-image-2.0; an album beyond it is cut down by the tool, which says so
    override val maxEditImages = 5

    override suspend fun generate(prompt: String, framing: ImageFraming): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        return draw("generations", request(prompt, framing))
    }

    override suspend fun edit(prompt: String, images: List<SourceImage>, framing: ImageFraming): ByteArray {
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        require(images.isNotEmpty()) { "At least one source image is required" }
        require(images.none { it.bytes.isEmpty() }) { "Image bytes must not be empty" }

        val sources = images.map { XaiImageSource("data:${it.contentType};base64,${Base64.getEncoder().encodeToString(it.bytes)}") }

        // `image` and `images` exclude each other, and one picture goes as `image`, the form xai documents
        // for a single edit. an edit left to auto is framed like the first of them.
        val request =
            if (sources.size == 1)
                request(prompt, framing).copy(image = sources.single())
            else
                request(prompt, framing).copy(images = sources)

        return draw("edits", request)
    }

    // `auto` is what an omitted quality means, and the models before grok-imagine-image-2.0 take no quality at all
    private fun request(prompt: String, framing: ImageFraming) =
        XaiImageRequest(
            model = config.model,
            prompt = prompt,
            aspectRatio = framing.aspectRatio,
            quality = config.quality.takeUnless { it == "auto" },
            responseFormat = "b64_json",
        )

    private suspend fun draw(path: String, request: XaiImageRequest): ByteArray {
        val response =
            runCatching {
                http.post("$XAI_IMAGES_URL/$path") {
                    bearerAuth(config.apiKey)
                    contentType(ContentType.Application.Json)

                    timeout {
                        requestTimeoutMillis = IMAGE_TIMEOUT.inWholeMilliseconds
                        socketTimeoutMillis = IMAGE_TIMEOUT.inWholeMilliseconds
                    }

                    setBody(request)
                }.body<XaiImageResponse>()
            }
                .getOrElse { e ->
                    e.rethrowIfCancellation()
                    throw (e as? HttpStatusException)?.takeIf { it.isModerated() }
                        ?.let { ImageModerationBlocked(ImageModerationStage.UNKNOWN, emptyList()) }
                        ?: e
                }

        val image = response.data.firstOrNull()

        // xai's own sdk reads a withheld picture off this flag rather than an error
        if (image?.respectModeration == false)
            throw ImageModerationBlocked(ImageModerationStage.OUTPUT, emptyList())

        val encoded = checkNotNull(image?.b64Json) { "xAI image response contained no image data" }
        val bytes = Base64.getDecoder().decode(encoded)

        check(bytes.isNotEmpty()) { "xAI image decoded to empty bytes" }

        return bytes
    }

    private fun HttpStatusException.isModerated(): Boolean =
        status == HttpStatusCode.BadRequest.value &&
                runCatching { (Json.parseToJsonElement(body.orEmpty()).jsonObject["code"] as? JsonPrimitive)?.contentOrNull }.getOrNull() == MODERATED_CODE
}

private val ImageFraming.aspectRatio: String?
    get() =
        when (this) {
            ImageFraming.SQUARE -> "1:1"
            ImageFraming.PORTRAIT -> "2:3"
            ImageFraming.LANDSCAPE -> "3:2"
            ImageFraming.STORY -> "9:16"
            ImageFraming.BANNER -> "16:9"
            ImageFraming.AUTO -> null
        }
