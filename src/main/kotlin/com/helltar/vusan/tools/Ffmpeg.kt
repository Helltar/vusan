package com.helltar.vusan.tools

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val FFMPEG_LOG_TAIL_CHARS = 500

private val log = KotlinLogging.logger("Ffmpeg")

/**
 * Runs `ffmpeg` with [args] (binary excluded) and reports whether it exited cleanly. Never throws: a missing
 * binary, a non-zero exit and a timeout all return `false`, so callers can fall back.
 */
internal suspend fun runFfmpeg(args: List<String>, ffmpegPath: String, timeout: Duration): Boolean =
    withContext(Dispatchers.IO) {
        val process =
            runCatching { ProcessBuilder(listOf(ffmpegPath) + args).redirectErrorStream(true).start() }
                .getOrElse {
                    it.rethrowIfCancellation()
                    log.warn(it) { "ffmpeg could not start binary=[$ffmpegPath]" }
                    return@withContext false
                }

        try {
            // read concurrently so the merged pipe cannot fill and deadlock the process; read errors are
            // swallowed so a destroyed stream never fails this coroutine.
            val output =
                async {
                    runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                }

            val finished = runInterruptible { process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS) }

            when {
                !finished -> {
                    process.destroyForcibly()
                    log.warn { "ffmpeg timed out after $timeout" }
                    false
                }

                process.exitValue() != 0 -> {
                    val tail = withTimeoutOrNull(2.seconds) { output.await() }.orEmpty().takeLast(FFMPEG_LOG_TAIL_CHARS)
                    log.warn { "ffmpeg exit ${process.exitValue()}: $tail" }
                    false
                }

                else -> true
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
