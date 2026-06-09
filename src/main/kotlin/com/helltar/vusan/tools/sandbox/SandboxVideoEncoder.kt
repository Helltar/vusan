package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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

            if (!runFfmpeg(input.toString(), output.toString()))
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

    private suspend fun runFfmpeg(inputPath: String, outputPath: String): Boolean = coroutineScope {
        val command =
            listOf(
                ffmpegPath,
                "-y",
                "-i", inputPath,
                // h264/yuv420p with even dimensions is what Telegram and players reliably autoplay
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p",
                "-movflags", "+faststart",
                "-an",
                outputPath
            )

        val process =
            runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
                .getOrElse {
                    it.rethrowIfCancellation()
                    log.warn(it) { "ffmpeg could not start binary=[$ffmpegPath]" }
                    return@coroutineScope false
                }

        try {
            // read concurrently so the merged stdout/stderr pipe cannot fill and deadlock the
            // process; swallow read errors so a destroyed stream never fails this coroutine.
            val outputDeferred =
                async {
                    runCatching {
                        process.inputStream.bufferedReader().use { it.readText() }
                    }.getOrDefault("")
                }

            val finished = runInterruptible { process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS) }

            when {
                !finished -> {
                    process.destroyForcibly()
                    log.warn { "ffmpeg timed out after $timeout" }
                    false
                }

                process.exitValue() != 0 -> {
                    val tail = withTimeoutOrNull(2.seconds) { outputDeferred.await() }.orEmpty().takeLast(500)
                    log.warn { "ffmpeg exit ${process.exitValue()}: $tail" }
                    false
                }

                else -> true
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
