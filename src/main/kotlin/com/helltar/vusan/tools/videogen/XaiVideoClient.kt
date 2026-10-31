package com.helltar.vusan.tools.videogen

import com.helltar.vusan.common.dataUrl
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.XaiVideoConfig
import com.helltar.vusan.infra.HttpStatusException
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.files.FileDownloadResult
import com.helltar.vusan.tools.imagegen.SelfImage
import com.helltar.vusan.tools.imagegen.SourceImage
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

private const val XAI_VIDEOS_URL = "https://api.x.ai/v1/videos"

// the only model that edits or extends a video; newer ones answer both with a 400
private const val EDIT_MODEL = "grok-imagine-video"

// frames, reference images or a voice make a reference-to-video request, capped at 720p: 1080p is a 400, not
// a smaller video
private const val REFERENCE_MAX_RESOLUTION = "720p"
private const val FULL_HD = "1080p"

// clips measured 2026-10-21 took 20 to 50 seconds; the docs allow several minutes for a long one
private val POLL_INTERVAL = 5.seconds
private val RENDER_DEADLINE = 10.minutes

// images and video travel inline, so uploads get longer than a plain call
private val UPLOAD_TIMEOUT = 2.minutes

// failures on xAI's own side; the first one measured passed on the next try. a fault in the request itself
// would fail again, so those are not repeated.
private val PASSING_FAILURES = setOf("internal_error", "service_unavailable")
private const val ATTEMPTS = 2

// xai documents pending, done, failed and expired; an added status such as queued is waited out, not read as
// the end
private val FINAL_STATUSES = setOf("done", "failed", "error", "expired", "cancelled")

// the finished address lives four hours and the render is paid for, so a failed download is retried a few
// times
private const val DOWNLOAD_ATTEMPTS = 3

// JPEG, PNG and WebP, judged by the bytes rather than the name
private val PICTURE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

// how the bot is named in a video without its photo, only the notes on its looks
private const val SELF_WITHOUT_PHOTO = "the main character"

/**
 * xAI's moderation withheld the video, signaled as a finished job with an empty address or a 400 coded as
 * moderated. Nothing in the request turns the filter off.
 */
class VideoModerationBlocked : IllegalStateException("xAI's moderation withheld the video")

/** A finished video, with its length where known. */
class RenderedVideo(val bytes: ByteArray, val durationSeconds: Int?)

/**
 * xAI's Imagine video API: a request starts a job, the job is polled until done, and the video is downloaded
 * from the temporary address it returns. Images and videos travel inline as data URLs, so nothing has to be
 * hosted.
 */
class XaiVideoClient(
    private val http: HttpClient,
    private val config: XaiVideoConfig,
    private val downloads: FileDownloadClient,
    private val pollInterval: Duration = POLL_INTERVAL,
) {

    /**
     * A new video from [prompt]: from nothing, opening on [startFrame], closing toward [endFrame], featuring
     * [references] named from `<IMAGE_0>` and [self], the bot, as `<SELF>`. [aspectRatio] is omitted where a
     * start frame decides it.
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
        // xai numbers images across `image` and `reference_images`: a start frame is <IMAGE_0> and shifts the
        // references by one, the bot's photo goes after them. a last frame is a separate field outside the
        // count.
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

    /** [video], an MP4 called [filename], with the change [prompt] describes, at its original length. */
    suspend fun edit(prompt: String, video: ByteArray, filename: String): RenderedVideo {
        val request = XaiVideoEditRequest(EDIT_MODEL, prompt, mp4Media(video, filename))

        return render("edits", { setBody(request) }) { it.video?.duration }
    }

    /**
     * [video], an MP4 called [filename] running [sourceSeconds], extended by [seconds] as one video. The
     * answer states only the added seconds while the file holds both, so the length is the sum.
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

    // the job renders and is billed whether or not it is polled, so a passing poll failure is retried until
    // the deadline rather than throwing the paid video away
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
 * The prompt as xAI reads it: references named from `<IMAGE_0>` shifted by [offset], and `<SELF>` replaced by
 * [selfName], or `null` when the bot is not in the video, which the tool already checked the prompt does not
 * ask for.
 */
internal fun xaiVideoPrompt(prompt: String, offset: Int, selfName: String?): String {
    val numbered = IMAGE_TAG.replace(prompt) { "<IMAGE_${it.groupValues[1].toInt() + offset}>" }

    return selfName?.let { numbered.replace(SELF_TAG, it) } ?: numbered
}

// a status that says nothing about the job: xai briefly out, or the answer lost on the way
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
