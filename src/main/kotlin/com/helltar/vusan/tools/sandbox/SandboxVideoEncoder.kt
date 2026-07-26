package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tools.runFfmpeg
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface SandboxVideoEncoder {

    suspend fun encodeToMp4(animation: ByteArray): ByteArray?
}

class FfmpegVideoEncoder(
    private val ffmpegPath: String = "ffmpeg",
    private val timeout: Duration = 60.seconds
) : SandboxVideoEncoder {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    override suspend fun encodeToMp4(animation: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        if (animation.isEmpty()) return@withContext null

        val workDir = Files.createTempDirectory("sandbox-video-")

        try {
            val input = workDir.resolve("input")
            val output = workDir.resolve("output.mp4")

            Files.write(input, animation)

            val command =
                listOf(
                    "-y",
                    "-i", input.toString(),
                    // h264/yuv420p with even dimensions is what Telegram and players reliably autoplay
                    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p",
                    "-movflags", "+faststart",
                    "-an",
                    output.toString()
                )

            if (!runFfmpeg(command, ffmpegPath, timeout))
                return@withContext null

            output.takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) }?.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            log.warn(e) { "ffmpeg animation encode failed" }
            null
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }
}
