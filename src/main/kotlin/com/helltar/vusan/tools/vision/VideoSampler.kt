package com.helltar.vusan.tools.vision

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tools.runFfmpeg
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Pulls out of a video the parts a language model can actually read: still frames and speech. */
interface VideoSampler {

    /** Up to [maxFrames] frames as JPEG bytes, in chronological order; empty when nothing could be read. */
    suspend fun sampleFrames(video: ByteArray, durationSeconds: Int?, maxFrames: Int): List<ByteArray>

    /** The audio track as mono 16 kHz m4a, or null when the video is silent or ffmpeg failed. */
    suspend fun extractAudio(video: ByteArray): ByteArray?
}

class FfmpegVideoSampler(
    private val ffmpegPath: String = "ffmpeg",
    private val timeout: Duration = 120.seconds
) : VideoSampler {

    private companion object {
        // frames are read for content, not detail, and eight of them at this width stay cheap to send.
        const val FRAME_MAX_WIDTH = 768
        const val FRAME_QUALITY = "6"

        // without a duration there is nothing to spread frames across, so they are taken at a fixed
        // interval from the start instead. only video documents arrive without one.
        const val FALLBACK_FRAME_INTERVAL_SECONDS = 3

        const val AUDIO_CHANNELS = "1"
        const val AUDIO_SAMPLE_RATE = "16000"
        const val AUDIO_BITRATE = "48k"

        val log = KotlinLogging.logger {}
    }

    override suspend fun sampleFrames(video: ByteArray, durationSeconds: Int?, maxFrames: Int): List<ByteArray> {
        require(maxFrames > 0) { "maxFrames must be positive" }

        return withWorkDir(video, "frames") { input, workDir ->
            val command =
                listOf(
                    "-hide_banner", "-nostdin", "-y",
                    "-i", input.toString(),
                    "-vf", "fps=${frameRate(durationSeconds, maxFrames)},scale='min($FRAME_MAX_WIDTH,iw)':-2",
                    "-frames:v", maxFrames.toString(),
                    "-q:v", FRAME_QUALITY,
                    workDir.resolve("frame-%03d.jpg").toString()
                )

            if (!runFfmpeg(command, ffmpegPath, timeout))
                return@withWorkDir emptyList()

            Files.newDirectoryStream(workDir, "frame-*.jpg")
                .use { entries -> entries.sortedBy { it.fileName.toString() } }
                .mapNotNull { it.readBytesOrNull() }
        }
            .orEmpty()
    }

    override suspend fun extractAudio(video: ByteArray): ByteArray? =
        withWorkDir(video, "audio") { input, workDir ->
            val output = workDir.resolve("audio.m4a")

            val command =
                listOf(
                    "-hide_banner", "-nostdin", "-y",
                    "-i", input.toString(),
                    "-vn",
                    "-ac", AUDIO_CHANNELS,
                    "-ar", AUDIO_SAMPLE_RATE,
                    // aac is ffmpeg's own encoder, so this works on builds without any external codec
                    "-c:a", "aac",
                    "-b:a", AUDIO_BITRATE,
                    output.toString()
                )

            // a video with no audio stream fails the run, which is the same "no transcript" answer
            if (!runFfmpeg(command, ffmpegPath, timeout)) null else output.readBytesOrNull()
        }

    // frames are spread evenly across the whole video: eight frames of a 40-second clip means one
    // every five seconds, expressed as the rational ffmpeg's fps filter expects.
    private fun frameRate(durationSeconds: Int?, maxFrames: Int): String =
        durationSeconds
            ?.takeIf { it > 0 }
            ?.let { "$maxFrames/$it" }
            ?: "1/$FALLBACK_FRAME_INTERVAL_SECONDS"

    private suspend fun <T> withWorkDir(video: ByteArray, label: String, block: suspend (Path, Path) -> T): T? =
        withContext(Dispatchers.IO) {
            if (video.isEmpty()) return@withContext null

            val workDir = Files.createTempDirectory("video-$label-")

            try {
                val input = workDir.resolve("input")
                Files.write(input, video)
                block(input, workDir)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn(e) { "ffmpeg video $label failed" }
                null
            } finally {
                workDir.toFile().deleteRecursively()
            }
        }

    private fun Path.readBytesOrNull(): ByteArray? =
        takeIf { Files.exists(it) }
            ?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
}
