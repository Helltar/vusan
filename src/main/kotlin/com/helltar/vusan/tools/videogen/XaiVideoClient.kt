package com.helltar.vusan.tools.videogen

import com.helltar.vusan.common.dataUrl
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.VideoProviderConfig
import com.helltar.vusan.config.XAI_API_BASE_URL
import com.helltar.vusan.infra.HttpStatusException
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.files.FileDownloadResult
import com.helltar.vusan.tools.imagegen.SelfImage
import com.helltar.vusan.tools.imagegen.SourceImage
import com.helltar.vusan.tools.isImagineModerated
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val XAI_VIDEOS_URL = "$XAI_API_BASE_URL/videos"

// the one model that edits a video or extends one; the newer ones answer both with a 400
private const val EDIT_MODEL = "grok-imagine-video"

// a first and a last frame, reference images or a voice make a reference-to-video request, which renders at
// most 720p: asked for 1080p it is a 400 rather than a smaller video
private const val REFERENCE_MAX_RESOLUTION = "720p"
private const val FULL_HD = "1080p"

// the clips measured on 2026-10-21 took 20 to 50 seconds; the docs allow several minutes for a long one
private val POLL_INTERVAL = 5.seconds
private val RENDER_DEADLINE = 10.minutes

// a request carries its images and its video inline, so it is given longer to upload than a plain call
private val UPLOAD_TIMEOUT = 2.minutes

// a job that failed on xAI's own side: the first one measured failed so, and the same request went through
// on the next try. what was wrong with the request itself fails the same way again, so it is not repeated.
private val PASSING_FAILURES = setOf("internal_error", "service_unavailable")
private const val ATTEMPTS = 2

// xai documents pending, done, failed and expired; a status it may add, such as queued, is waited out rather
// than read as the end of the job
private val FINAL_STATUSES = setOf("done", "failed", "error", "expired", "cancelled")

// the finished address lives four hours and the render behind it is paid for, so a failed download is made
// again: the download client reports a status as plain text, and a few more tries at one address cost nothing
private const val DOWNLOAD_ATTEMPTS = 3

// what xAI reads as a picture: JPEG, PNG and WebP, judged by the bytes rather than a name
private val PICTURE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

// how the bot is named in a video where it has no photo, only the notes on how it looks
private const val SELF_WITHOUT_PHOTO = "the main character"

/**
 * xAI made the video and its moderation withheld it, which it says two ways: a finished job whose address
 * is empty, or a 400 coded as moderated. Nothing in the request turns the filter off.
 */
class VideoModerationBlocked : IllegalStateException("xAI's moderation withheld the video")

/** A finished video, with its length where it is known. */
class RenderedVideo(val bytes: ByteArray, val durationSeconds: Int?)

/**
 * xAI's Imagine video API. A request starts a job, the job is asked about until it is done, and the
 * finished video is downloaded from the temporary address it answers with.
 *
 * Every image and video travels inline as a data URL, so nothing has to be hosted for xAI to fetch.
 */
class XaiVideoClient(
    private val http: HttpClient,
    private val config: VideoProviderConfig.Xai,
    private val downloads: FileDownloadClient,
    private val pollInterval: Duration = POLL_INTERVAL,
) {

    /**
     * A new video from [prompt]: from nothing, from a [startFrame] it opens on, toward an [endFrame] it
     * closes on, and featuring [references] the prompt names from `<IMAGE_0>`, and [self], the bot, as
     * `<SELF>`. [aspectRatio] is left out where a start frame decides it.
     */
    suspend fun generate(
        prompt: String,
        seconds: Int,
        aspectRatio: String,
        sound: Boolean,
        startFrame: SourceImage? = null,
        endFrame: SourceImage? = null,
        references: List<SourceImage> = emptyList(),
        self: SelfImage? = null,
    ): RenderedVideo {
        // xai numbers the images of the prompt across `image` and `reference_images`: a start frame is
        // <IMAGE_0> and moves the references up by one, and the bot's photo goes after them. a last frame
        // is a field of its own, which the reference-to-video docs leave out of the count.
        val photo = self?.reference
        val offset = if (startFrame != null) 1 else 0
        val selfName = self?.let { if (photo != null) "<IMAGE_${references.size + offset}>" else SELF_WITHOUT_PHOTO }
        val pictures = references + listOfNotNull(photo)
        val voice = config.voice?.takeIf { selfName != null }
        val referenced = endFrame != null || pictures.isNotEmpty() || voice != null

        val described =
            listOfNotNull(
                xaiVideoPrompt(prompt, offset, selfName),
                self?.appearance?.let { "About $selfName: $it" },
                voice?.let { "When $selfName speaks, it is with the voice <AUDIO_0>." },
            ).joinToString("\n\n")

        val request =
            XaiVideoGenerationRequest(
                model = config.model,
                prompt = described,
                duration = seconds,
                resolution = if (referenced && config.resolution == FULL_HD) REFERENCE_MAX_RESOLUTION else config.resolution,
                aspectRatio = aspectRatio.takeIf { startFrame == null },
                generateAudio = false.takeUnless { sound },
                image = startFrame?.media(),
                lastFrame = endFrame?.media(),
                referenceImages = pictures.map { it.media() }.ifEmpty { null },
                referenceAudios = voice?.let { listOf(XaiVoice(it)) },
            )

        return render("generations", { setBody(request) }) { it.video?.duration }
    }

    /** [video], an MP4 called [filename], with the change [prompt] describes, as long as it was. */
    suspend fun edit(prompt: String, video: ByteArray, filename: String): RenderedVideo {
        val request = XaiVideoEditRequest(EDIT_MODEL, prompt, mp4Media(video, filename))

        return render("edits", { setBody(request) }) { it.video?.duration }
    }

    /**
     * [video], an MP4 called [filename] that runs [sourceSeconds], carried on for [seconds] more as one
     * video. The answer states the added seconds alone while the file holds both parts, so the length is
     * the source's plus the request's.
     */
    suspend fun extend(prompt: String, video: ByteArray, filename: String, seconds: Int, sourceSeconds: Int?): RenderedVideo {
        val request = XaiVideoExtensionRequest(EDIT_MODEL, prompt, mp4Media(video, filename), seconds)

        return render("extensions", { setBody(request) }) { sourceSeconds?.plus(seconds) }
    }

    private suspend fun render(path: String, body: HttpRequestBuilder.() -> Unit, durationOf: (XaiVideoStatus) -> Int?): RenderedVideo =
        runCatching { renderJob(path, body, durationOf) }
            .getOrElse { e ->
                e.rethrowIfCancellation()
                throw (e as? HttpStatusException)?.takeIf { it.isImagineModerated() }?.let { VideoModerationBlocked() } ?: e
            }

    private suspend fun renderJob(path: String, body: HttpRequestBuilder.() -> Unit, durationOf: (XaiVideoStatus) -> Int?): RenderedVideo {
        var attempt = 1

        while (true) {
            val job =
                http.post("$XAI_VIDEOS_URL/$path") {
                    bearerAuth(config.apiKey)
                    contentType(ContentType.Application.Json)

                    timeout {
                        requestTimeoutMillis = UPLOAD_TIMEOUT.inWholeMilliseconds
                        socketTimeoutMillis = UPLOAD_TIMEOUT.inWholeMilliseconds
                    }

                    body()
                }.body<XaiVideoJob>()

            val status = await(job.requestId)
            val failure = status.error

            if (status.status == "done") return finished(status, durationOf(status))

            if (failure?.code in PASSING_FAILURES && attempt < ATTEMPTS) {
                log.warn { "xAI video job failed on its side, trying again: path=[$path] code=[${failure?.code}] message=[${failure?.message}]" }
                attempt++
                continue
            }

            error("The video job ended ${status.status}: ${failure?.message ?: "xAI gave no reason"}")
        }
    }

    // the job renders, and is billed, whether or not anyone asks about it, so a look that failed in passing
    // is made again until the deadline rather than throwing the paid video away
    private suspend fun await(requestId: String): XaiVideoStatus {
        val deadline = TimeSource.Monotonic.markNow() + RENDER_DEADLINE

        while (true) {
            delay(pollInterval)

            val status =
                runCatching { http.get("$XAI_VIDEOS_URL/${requestId.encodeURLPathPart()}") { bearerAuth(config.apiKey) }.body<XaiVideoStatus>() }
                    .getOrElse { e ->
                        e.rethrowIfCancellation()

                        if (!e.isPassing()) throw e

                        log.warn { "xAI video poll failed, asking again: requestId=[$requestId] error=[${e.message}]" }
                        null
                    }

            if (status != null && status.status in FINAL_STATUSES) return status

            check(deadline.hasNotPassedNow()) { "The video was not ready after ${RENDER_DEADLINE.inWholeMinutes} minutes" }
        }
    }

    private suspend fun finished(status: XaiVideoStatus, durationSeconds: Int?): RenderedVideo {
        val url = status.video?.takeIf { it.respectModeration }?.url?.takeIf { it.isNotBlank() } ?: throw VideoModerationBlocked()

        val download = retrying(DOWNLOAD_ATTEMPTS) { downloads.download(url, "video.mp4") }

        return when (download) {
            is FileDownloadResult.Success -> RenderedVideo(download.bytes, durationSeconds)
            is FileDownloadResult.TooLarge -> error("The finished video is larger than can be sent (${download.sizeBytes ?: "over the limit"} bytes)")
        }
    }

    private suspend fun <T> retrying(attempts: Int, block: suspend () -> T): T {
        repeat(attempts - 1) {
            runCatching { return block() }
                .onFailure { e ->
                    e.rethrowIfCancellation()
                    log.warn { "xAI video download failed, trying again: error=[${e.message}]" }
                    delay(pollInterval)
                }
        }

        return block()
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

/**
 * The prompt as xAI reads it: the references the model names from `<IMAGE_0>` moved up by [offset], and
 * `<SELF>` turned into [selfName], the bot as xAI knows it — `null` when the bot is not in the video, which
 * the tool has already made sure the prompt does not ask for.
 */
internal fun xaiVideoPrompt(prompt: String, offset: Int, selfName: String?): String {
    val numbered = IMAGE_TAG.replace(prompt) { "<IMAGE_${it.groupValues[1].toInt() + offset}>" }

    return selfName?.let { numbered.replace(SELF_TAG, it) } ?: numbered
}

// a status that says nothing about the job: xai's side out for a moment, or the answer lost on the way
private fun Throwable.isPassing(): Boolean =
    this is IOException ||
            (this is HttpStatusException && (status == HttpStatusCode.TooManyRequests.value || status >= HttpStatusCode.InternalServerError.value))

private fun SourceImage.media(): XaiMedia {
    require(contentType in PICTURE_TYPES) { "`$filename` is not a JPEG, PNG or WebP picture" }

    return XaiMedia(dataUrl(contentType, bytes))
}

// an MP4 opens with its `ftyp` box, four bytes in
private fun mp4Media(bytes: ByteArray, filename: String): XaiMedia {
    require(bytes.size > MP4_BRAND_END && bytes.decodeToString(MP4_BRAND_START, MP4_BRAND_END) == "ftyp") { "`$filename` is not an MP4 video" }

    return XaiMedia(dataUrl("video/mp4", bytes))
}

private const val MP4_BRAND_START = 4
private const val MP4_BRAND_END = 8
