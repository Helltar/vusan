package com.helltar.vusan.tools.videogen

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.VideoProviderConfig
import com.helltar.vusan.config.XAI_API_BASE_URL
import com.helltar.vusan.infra.HttpStatusException
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.files.FileDownloadResult
import com.helltar.vusan.tools.isImagineModerated
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.delay
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

/**
 * xAI made the video and its moderation withheld it, which it says two ways: a finished job whose address
 * is empty, or a 400 coded as moderated. Nothing in the request turns the filter off.
 */
class VideoModerationBlocked : IllegalStateException("xAI's moderation withheld the video")

/** A finished video, with its length where xAI states the whole of it. */
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
     * closes on, and featuring [references] the prompt names as `<IMAGE_n>`. [aspectRatio] is left out
     * where a start frame decides it, and [speaker], the bot as the prompt names it, is given the
     * configured voice as `<AUDIO_0>`.
     */
    suspend fun generate(
        prompt: String,
        seconds: Int,
        aspectRatio: String,
        sound: Boolean,
        startFrame: String? = null,
        endFrame: String? = null,
        references: List<String> = emptyList(),
        speaker: String? = null,
    ): RenderedVideo {
        val voice = config.voice?.takeIf { speaker != null }
        val referenced = endFrame != null || references.isNotEmpty() || voice != null

        val request =
            XaiVideoGenerationRequest(
                model = config.model,
                prompt = voice?.let { "$prompt\n\nWhen $speaker speaks, it is with the voice <AUDIO_0>." } ?: prompt,
                duration = seconds,
                resolution = if (referenced && config.resolution == FULL_HD) REFERENCE_MAX_RESOLUTION else config.resolution,
                aspectRatio = aspectRatio.takeIf { startFrame == null },
                generateAudio = false.takeUnless { sound },
                image = startFrame?.let(::XaiMedia),
                lastFrame = endFrame?.let(::XaiMedia),
                referenceImages = references.map(::XaiMedia).ifEmpty { null },
                referenceAudios = voice?.let { listOf(XaiVoice(it)) },
            )

        return render("generations", { setBody(request) }) { it.video?.duration }
    }

    /** [video] with the change [prompt] describes, as long as it was. */
    suspend fun edit(prompt: String, video: String): RenderedVideo {
        val request = XaiVideoEditRequest(EDIT_MODEL, prompt, XaiMedia(video))

        return render("edits", { setBody(request) }) { it.video?.duration }
    }

    /**
     * [video] carried on for [seconds] more, as one video. The answer states the added seconds alone,
     * while the file holds the source and the extension together, so no length is taken from it.
     */
    suspend fun extend(prompt: String, video: String, seconds: Int): RenderedVideo {
        val request = XaiVideoExtensionRequest(EDIT_MODEL, prompt, XaiMedia(video), seconds)

        return render("extensions", { setBody(request) }) { null }
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

    private suspend fun await(requestId: String): XaiVideoStatus {
        val deadline = TimeSource.Monotonic.markNow() + RENDER_DEADLINE

        while (true) {
            delay(pollInterval)

            val status = http.get("$XAI_VIDEOS_URL/${requestId.encodeURLPathPart()}") { bearerAuth(config.apiKey) }.body<XaiVideoStatus>()

            if (status.status != "pending") return status

            check(deadline.hasNotPassedNow()) { "The video was not ready after ${RENDER_DEADLINE.inWholeMinutes} minutes" }
        }
    }

    private suspend fun finished(status: XaiVideoStatus, durationSeconds: Int?): RenderedVideo {
        val url = status.video?.takeIf { it.respectModeration }?.url?.takeIf { it.isNotBlank() } ?: throw VideoModerationBlocked()

        return when (val download = downloads.download(url, "video.mp4")) {
            is FileDownloadResult.Success -> RenderedVideo(download.bytes, durationSeconds)
            is FileDownloadResult.TooLarge -> error("The finished video is larger than can be sent (${download.sizeBytes ?: "over the limit"} bytes)")
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
