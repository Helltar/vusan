package com.helltar.vusan.tools.youtube

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// stderr is merged into stdout (`redirectErrorStream(true)`), so one stream carries everything.
data class YtDlpCommandResult(
    val stdout: String,
    val exitCode: Int,
    val timedOut: Boolean = false
)

class YtDlpClient(
    private val cookiesFile: String? = null,
    private val timeoutSeconds: Long = 180
) {

    private companion object {
        const val YT_DLP_BINARY = "yt-dlp"
        const val SEARCH_RESULT_LIMIT = 5
        const val FORMAT_UNAVAILABLE_MARKER = "Requested format is not available"
        const val VIDEO_MAX_FILE_SIZE_MB = 50
        val VIDEO_HEIGHT_CAPS = listOf(720, 480, 360)
        val json = Json { ignoreUnknownKeys = true }
        val log = KotlinLogging.logger {}
    }

    private data class DownloadAttempt<out T>(val result: YtDlpResult<T>, val retryable: Boolean = false)
    private data class YtDlpSearchCandidate(val url: String)

    private val diagnosticsMutex = Mutex()
    private var cachedDiagnostics: String? = null

    private suspend fun getRuntimeDiagnostics(): String = diagnosticsMutex.withLock {
        cachedDiagnostics ?: run {
            val versionResult = runCommand(listOf(YT_DLP_BINARY, "--version"))

            val version =
                when {
                    versionResult.timedOut -> "timeout"

                    versionResult.exitCode == 0 -> {
                        versionResult.stdout.trim().lineSequence().firstOrNull().orEmpty()
                            .ifBlank { "empty" }
                    }

                    else -> "exit-${versionResult.exitCode}:${versionResult.stdout.trim().take(120)}"
                }

            val diagnostics = "binary=[$YT_DLP_BINARY] version=[$version]"
            cachedDiagnostics = diagnostics
            diagnostics
        }
    }

    suspend fun downloadTrack(query: String, maxFileSizeMb: Int = 45): YtDlpResult<YtDlpTrack> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "Query must not be blank" }
            require(maxFileSizeMb in 1..50) { "maxFileSizeMb must be between 1 and 50" }

            val workDir = Files.createTempDirectory("ytdlp-audio-")

            try {
                runAudioDownload(workDir, query, maxFileSizeMb)
            } finally {
                workDir.toFile().deleteRecursively()
            }
        }

    suspend fun downloadVideo(query: String, maxFileSizeMb: Int = VIDEO_MAX_FILE_SIZE_MB): YtDlpResult<YtDlpVideo> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "Query must not be blank" }
            require(maxFileSizeMb in 1..50) { "maxFileSizeMb must be between 1 and 50" }

            val workDir = Files.createTempDirectory("ytdlp-video-")

            try {
                runVideoDownload(workDir, query, maxFileSizeMb)
            } finally {
                workDir.toFile().deleteRecursively()
            }
        }

    private suspend fun runAudioDownload(workDir: Path, query: String, maxFileSizeMb: Int): YtDlpResult<YtDlpTrack> =
        downloadFromCandidates(
            kind = "audio",
            workDir = workDir,
            query = query,
            maxFileSizeMb = maxFileSizeMb,
            resolveCandidates = { searchCandidates(query) },
            describeSuccess = { "title=[${it.title}] performer=[${it.performer}]" },
            downloadCandidate = { attemptDir, url -> downloadAudioCandidate(attemptDir, url, query, maxFileSizeMb) }
        )

    private suspend fun <T> downloadFromCandidates(
        kind: String,
        workDir: Path,
        query: String,
        maxFileSizeMb: Int,
        resolveCandidates: suspend () -> List<YtDlpSearchCandidate>,
        describeSuccess: (T) -> String,
        downloadCandidate: suspend (attemptDir: Path, url: String) -> DownloadAttempt<T>
    ): YtDlpResult<T> {
        val diagnostics = getRuntimeDiagnostics()

        log.info {
            "yt-dlp $kind download start query=[${query.take(120)}] " +
                    "maxFileSizeMb=$maxFileSizeMb $diagnostics ${authDiagnostics()}"
        }

        val candidates = resolveCandidates()

        log.info { "yt-dlp $kind resolved ${candidates.size} candidate(s) for query=[${query.take(120)}]" }

        if (candidates.isEmpty()) {
            log.warn { "yt-dlp found no YouTube candidates for query=[${query.take(120)}]" }
            return YtDlpResult.NotFound
        }

        val retryableFailures = mutableListOf<YtDlpResult.Failure>()
        var lastTooLarge: YtDlpResult.TooLarge? = null

        candidates.forEachIndexed { index, candidate ->
            val label = "$kind candidate ${index + 1}/${candidates.size}"
            log.info { "yt-dlp trying $label url=[${candidate.url}] query=[${query.take(120)}]" }

            val attemptDir = Files.createDirectory(workDir.resolve("candidate-$index"))
            val attempt = downloadCandidate(attemptDir, candidate.url)

            when (val result = attempt.result) {
                is YtDlpResult.Success -> {
                    log.info { "yt-dlp $label succeeded ${describeSuccess(result.value)}" }
                    return result
                }

                is YtDlpResult.AuthRequired -> {
                    log.warn { "yt-dlp $label requires auth url=[${candidate.url}] ${authDiagnostics()}" }
                    return result
                }

                is YtDlpResult.TooLarge -> {
                    log.warn {
                        "yt-dlp $label is too large url=[${candidate.url}] sizeBytes=${result.sizeBytes}, " +
                                "trying next candidate"
                    }
                    lastTooLarge = result
                }

                is YtDlpResult.NotFound -> {
                    log.info { "yt-dlp $label was not usable url=[${candidate.url}], trying next candidate" }
                }

                is YtDlpResult.Failure -> {
                    log.warn {
                        "yt-dlp $label failed retryable=${attempt.retryable} url=[${candidate.url}]: ${
                            result.reason.take(
                                300
                            )
                        }"
                    }
                    if (!attempt.retryable) return result
                    retryableFailures += result
                }
            }
        }

        val allFormatUnavailable =
            retryableFailures.size == candidates.size &&
                    retryableFailures.all { it.reason.contains(FORMAT_UNAVAILABLE_MARKER, ignoreCase = true) }

        if (allFormatUnavailable) {
            return YtDlpResult.Failure(
                "yt-dlp found no available formats for any of ${candidates.size} " +
                        "YouTube candidates on this server. Check the server yt-dlp version and YouTube cookies."
            )
        }

        return lastTooLarge ?: retryableFailures.lastOrNull() ?: YtDlpResult.NotFound
    }

    private suspend fun <T> runMediaDownload(
        command: List<String>,
        outputFile: Path,
        url: String,
        query: String,
        maxFileSizeMb: Int,
        buildPayload: (bytes: ByteArray, info: YtDlpInfo) -> T
    ): DownloadAttempt<T> {
        val commandResult = runCommand(command)

        if (commandResult.timedOut) {
            log.warn { "yt-dlp download timed out after ${timeoutSeconds}s url=[$url] query=[${query.take(120)}]" }
            return DownloadAttempt(YtDlpResult.Failure("yt-dlp timed out after ${timeoutSeconds}s"))
        }

        if (commandResult.exitCode != 0) {
            return classifyDownloadError(commandResult, url, query, maxFileSizeMb)
        }

        val info = parseInfoJson(commandResult.stdout)
            ?: return DownloadAttempt(YtDlpResult.Failure("yt-dlp produced no metadata"))

        if (!Files.exists(outputFile)) {
            // with --max-filesize yt-dlp skips the oversized download and still exits 0
            if (commandResult.stdout.containsAny("File is larger than max-filesize")) {
                log.warn { "yt-dlp download rejected by max-filesize url=[$url] maxFileSizeMb=$maxFileSizeMb" }
                return DownloadAttempt(YtDlpResult.TooLarge(sizeBytes = maxFileSizeMb.toLong() * 1024 * 1024))
            }

            return DownloadAttempt(YtDlpResult.Failure("yt-dlp finished but no output file at $outputFile"))
        }

        val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(outputFile) }

        if (bytes.size > maxFileSizeMb * 1024 * 1024) {
            log.warn {
                "yt-dlp download exceeds Telegram limit url=[$url] bytes=${bytes.size} maxFileSizeMb=$maxFileSizeMb"
            }

            return DownloadAttempt(YtDlpResult.TooLarge(sizeBytes = bytes.size.toLong()))
        }

        return DownloadAttempt(YtDlpResult.Success(buildPayload(bytes, info)))
    }

    private suspend fun searchCandidates(query: String): List<YtDlpSearchCandidate> {
        val command =
            buildList {
                add(YT_DLP_BINARY)
                add("--ignore-config")
                add("--no-warnings")
                addAll(
                    listOf(
                        "--dump-single-json",
                        "--flat-playlist",
                        "--playlist-end",
                        SEARCH_RESULT_LIMIT.toString()
                    )
                )
                addAll(authArgs())
                add("ytsearch$SEARCH_RESULT_LIMIT:$query")
            }

        val result = runCommand(command)

        if (result.timedOut) {
            log.warn {
                "yt-dlp search timed out after ${timeoutSeconds}s for query=[${query.take(120)}] ${authDiagnostics()}"
            }

            return emptyList()
        }

        if (result.exitCode != 0) {
            log.warn {
                "yt-dlp search exit ${result.exitCode} for query=[${query.take(120)}] " +
                        "${authDiagnostics()}: ${result.stdout.take(500)}"
            }

            return emptyList()
        }

        val candidates = parseSearchCandidates(result.stdout)

        log.info { "yt-dlp search parsed ${candidates.size} candidate URL(s) for query=[${query.take(120)}]" }

        return candidates
    }

    private suspend fun downloadAudioCandidate(
        workDir: Path,
        url: String,
        query: String,
        maxFileSizeMb: Int
    ): DownloadAttempt<YtDlpTrack> {
        val command =
            buildList {
                add(YT_DLP_BINARY)
                add("--ignore-config")
                addAll(listOf("-x", "--audio-format", "m4a"))
                addAll(listOf("--format", "bestaudio/best"))
                addAll(listOf("--no-playlist", "--no-warnings"))
                addAll(listOf("--max-filesize", "${maxFileSizeMb}M"))
                add("--print-json")
                addAll(authArgs())
                addAll(listOf("-o", workDir.resolve("audio.%(ext)s").toString()))
                add(url)
            }

        return runMediaDownload(command, workDir.resolve("audio.m4a"), url, query, maxFileSizeMb) { bytes, info ->
            YtDlpTrack(
                bytes = bytes,
                title = info.track ?: info.title ?: "Unknown",
                performer = info.artist ?: info.uploader ?: info.channel ?: "Unknown",
                durationSeconds = info.duration?.toInt(),
                sourceUrl = info.webpageUrl
            )
        }
    }

    private suspend fun runVideoDownload(workDir: Path, query: String, maxFileSizeMb: Int): YtDlpResult<YtDlpVideo> =
        downloadFromCandidates(
            kind = "video",
            workDir = workDir,
            query = query,
            maxFileSizeMb = maxFileSizeMb,
            resolveCandidates = { videoCandidates(query) },
            describeSuccess = { "title=[${it.title}] height=${it.height}" },
            downloadCandidate = { attemptDir, url -> downloadVideoCandidate(attemptDir, url, query, maxFileSizeMb) }
        )

    private suspend fun downloadVideoCandidate(
        workDir: Path,
        url: String,
        query: String,
        maxFileSizeMb: Int
    ): DownloadAttempt<YtDlpVideo> {
        var lastTooLarge: YtDlpResult.TooLarge? = null

        VIDEO_HEIGHT_CAPS.forEach { cap ->
            val capDir = Files.createDirectory(workDir.resolve("h$cap"))
            val attempt = downloadVideoAtHeight(capDir, url, query, maxFileSizeMb, cap)
            val result = attempt.result

            if (result is YtDlpResult.TooLarge) {
                lastTooLarge = result

                log.info {
                    "yt-dlp video too large at height<=$cap url=[$url] sizeBytes=${result.sizeBytes}, " +
                            "stepping resolution down"
                }
            } else {
                // success, or a non-size error (auth/extract/format) that a lower resolution would not fix.
                return attempt
            }
        }

        return DownloadAttempt(lastTooLarge ?: YtDlpResult.TooLarge(sizeBytes = maxFileSizeMb.toLong() * 1024 * 1024))
    }

    private suspend fun downloadVideoAtHeight(
        workDir: Path,
        url: String,
        query: String,
        maxFileSizeMb: Int,
        heightCap: Int
    ): DownloadAttempt<YtDlpVideo> {
        val command =
            buildList {
                add(YT_DLP_BINARY)
                add("--ignore-config")
                addAll(listOf("--format", "bv*[height<=$heightCap]+ba/b[height<=$heightCap]/b"))
                addAll(listOf("--format-sort", "res:$heightCap,vcodec:h264,ext:mp4:m4a"))
                addAll(listOf("--merge-output-format", "mp4", "--remux-video", "mp4"))
                addAll(listOf("--write-thumbnail", "--convert-thumbnails", "jpg"))
                addAll(listOf("--no-playlist", "--no-warnings"))
                addAll(listOf("--max-filesize", "${maxFileSizeMb}M"))
                add("--print-json")
                addAll(authArgs())
                addAll(listOf("-o", workDir.resolve("video.%(ext)s").toString()))
                add(url)
            }

        return runMediaDownload(command, workDir.resolve("video.mp4"), url, query, maxFileSizeMb) { bytes, info ->
            YtDlpVideo(
                bytes = bytes,
                title = info.title ?: "Unknown",
                uploader = info.uploader ?: info.channel,
                durationSeconds = info.duration?.toInt(),
                width = info.width,
                height = info.height,
                thumbnailBytes = loadThumbnail(workDir.resolve("video.jpg"), url),
                sourceUrl = info.webpageUrl
            )
        }
    }

    // telegram falls back to a black placeholder when a bot upload has no preview, so a missing thumbnail is not fatal
    private fun loadThumbnail(path: Path, url: String): ByteArray? =
        runCatching { Files.readAllBytes(path).asTelegramVideoThumbnail() }
            .getOrNull()
            .also { if (it == null) log.info { "yt-dlp video thumbnail unavailable at $path url=[$url]" } }

    private suspend fun videoCandidates(query: String): List<YtDlpSearchCandidate> {
        val trimmed = query.trim()

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return listOf(YtDlpSearchCandidate(trimmed))
        }

        return searchCandidates(query)
    }

    private suspend fun classifyDownloadError(
        commandResult: YtDlpCommandResult,
        url: String,
        query: String,
        maxFileSizeMb: Int
    ): DownloadAttempt<Nothing> {
        val output = commandResult.stdout

        return when {
            output.containsAny("File is larger than max-filesize") -> {
                log.warn { "yt-dlp download rejected by max-filesize url=[$url] maxFileSizeMb=$maxFileSizeMb" }
                DownloadAttempt(YtDlpResult.TooLarge(sizeBytes = maxFileSizeMb.toLong() * 1024 * 1024))
            }

            output.containsAny("Sign in to confirm you", "confirm your age", "cookies-from-browser") -> {
                log.warn {
                    "yt-dlp download requires auth url=[$url] query=[${query.take(120)}] " +
                            "${authDiagnostics()}: ${output.take(500)}"
                }

                DownloadAttempt(YtDlpResult.AuthRequired)
            }

            output.containsAny("No video results", "Unable to extract") -> {
                log.warn {
                    "yt-dlp download could not extract candidate url=[$url] " +
                            "query=[${query.take(120)}]: ${output.take(500)}"
                }

                DownloadAttempt(YtDlpResult.NotFound, retryable = true)
            }

            else -> {
                val retryable = output.contains(FORMAT_UNAVAILABLE_MARKER, ignoreCase = true)

                log.warn {
                    "yt-dlp download exit ${commandResult.exitCode} retryable=$retryable " +
                            "query=[${query.take(120)}] url=[$url]: ${output.take(500)}"
                }

                if (retryable) logUnavailableFormatsDiagnostics(url, query)

                DownloadAttempt(
                    YtDlpResult.Failure("yt-dlp exit ${commandResult.exitCode}: ${output.take(200)}"),
                    retryable = retryable
                )
            }
        }
    }

    private suspend fun runCommand(command: List<String>): YtDlpCommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()

        try {
            val outputDeferred = async { process.inputStream.bufferedReader().use { it.readText() } }
            val finishedInTime = runInterruptible { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }

            if (!finishedInTime) {
                process.destroyForcibly()
                val stdout = outputDeferred.awaitWithin(1.seconds)
                YtDlpCommandResult(stdout = stdout, exitCode = -1, timedOut = true)
            } else {
                val stdout = outputDeferred.awaitWithin(5.seconds)
                YtDlpCommandResult(stdout = stdout, exitCode = process.exitValue())
            }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private suspend fun Deferred<String>.awaitWithin(timeout: Duration): String =
        try {
            withTimeout(timeout) { await() }
        } catch (e: TimeoutCancellationException) {
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }

    private fun parseInfoJson(stdout: String): YtDlpInfo? {
        val firstLine = stdout.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: return null
        return runCatching { json.decodeFromString<YtDlpInfo>(firstLine) }.getOrNull()
    }

    private fun parseSearchCandidates(stdout: String): List<YtDlpSearchCandidate> {
        val firstLine =
            stdout.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
                ?: return emptyList()

        val search =
            runCatching { json.decodeFromString<YtDlpSearchResult>(firstLine) }.getOrNull()
                ?: return emptyList()

        return search.entries.orEmpty().mapNotNull { entry ->
            val directUrl = entry.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            val videoId = entry.id ?: entry.url?.takeUnless { it.startsWith("http://") || it.startsWith("https://") }

            val url =
                entry.webpageUrl
                    ?: directUrl
                    ?: videoId?.let { "https://www.youtube.com/watch?v=$it" }

            url?.let { YtDlpSearchCandidate(it) }
        }.distinctBy { it.url }
    }

    private fun authArgs(): List<String> =
        cookiesFile?.takeUnless { it.isBlank() }?.let { listOf("--cookies", it) }
            ?: emptyList()

    private suspend fun logUnavailableFormatsDiagnostics(url: String, query: String) {
        val command =
            buildList {
                add(YT_DLP_BINARY)
                add("--ignore-config")
                add("--no-warnings")
                add("--list-formats")
                addAll(authArgs())
                add(url)
            }

        log.info { "yt-dlp list-formats start url=[$url] query=[${query.take(120)}] ${authDiagnostics()}" }

        val result = runCommand(command)
        val output = result.stdout.trim()

        when {
            result.timedOut -> log.warn { "yt-dlp list-formats timed out url=[$url] query=[${query.take(120)}]" }

            result.exitCode != 0 -> log.warn {
                "yt-dlp list-formats exit ${result.exitCode} url=[$url] " +
                        "query=[${query.take(120)}]: ${output.take(2000)}"
            }

            else -> log.warn {
                "yt-dlp list-formats output url=[$url] query=[${query.take(120)}]: ${output.take(3000)}"
            }
        }
    }

    private fun authDiagnostics(): String {
        cookiesFile?.takeUnless { it.isBlank() }?.let { return cookieFileDiagnostics(it) }
        return "auth=none"
    }

    private fun cookieFileDiagnostics(file: String): String =
        runCatching {
            val path = Path.of(file)
            val exists = Files.exists(path)
            val readable = Files.isReadable(path)

            val sizeBytes =
                if (exists)
                    runCatching { Files.size(path).toString() }.getOrDefault("unknown")
                else
                    "missing"

            "auth=cookies-file path=[$file] exists=$exists readable=$readable sizeBytes=$sizeBytes"
        }.getOrElse { error ->
            "auth=cookies-file path=[$file] invalidPath=[${error.message}]"
        }
}

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any { contains(it, ignoreCase = true) }

private const val THUMBNAIL_MAX_SIDE = 320
private const val THUMBNAIL_MAX_BYTES = 200 * 1024

/**
 * Converts image bytes into a Telegram-compliant video thumbnail: JPEG, at most
 * [THUMBNAIL_MAX_SIDE] px per side, under [THUMBNAIL_MAX_BYTES]. Returns null for
 * undecodable input.
 */
internal fun ByteArray.asTelegramVideoThumbnail(): ByteArray? {
    val source = runCatching { ImageIO.read(inputStream()) }.getOrNull() ?: return null
    val scale = (THUMBNAIL_MAX_SIDE.toDouble() / maxOf(source.width, source.height)).coerceAtMost(1.0)
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)

    // redraw onto an alpha-free canvas so the jpeg writer accepts any source color model
    val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    canvas.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        drawImage(source, 0, 0, width, height, null)
        dispose()
    }

    val output = ByteArrayOutputStream()

    if (!ImageIO.write(canvas, "jpg", output)) return null

    return output.toByteArray().takeIf { it.size <= THUMBNAIL_MAX_BYTES }
}
