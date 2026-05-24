package com.helltar.vusan.tools.youtube

import io.github.oshai.kotlinlogging.KotlinLogging
import korlibs.time.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class YtDlpCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false
)

class YtDlpClient(
    private val ytDlpPath: String = "yt-dlp",
    private val cookiesFile: String? = null,
    private val timeoutSeconds: Long = 180
) {

    private companion object {
        const val SEARCH_RESULT_LIMIT = 5
        const val FORMAT_UNAVAILABLE_MARKER = "Requested format is not available"
        val json = Json { ignoreUnknownKeys = true }
        val log = KotlinLogging.logger {}
    }

    private data class DownloadAttempt(val result: YtDlpResult, val retryable: Boolean = false)
    private data class YtDlpSearchCandidate(val url: String)

    private val diagnosticsMutex = Mutex()
    private var cachedDiagnostics: String? = null

    private suspend fun getRuntimeDiagnostics(): String = diagnosticsMutex.withLock {
        cachedDiagnostics ?: run {
            val versionResult = runCommand(listOf(ytDlpPath, "--version"))

            val version =
                when {
                    versionResult.timedOut -> "timeout"
                    versionResult.exitCode == 0 -> versionResult.stdout.trim().lineSequence().firstOrNull().orEmpty().ifBlank { "empty" }
                    else -> "exit-${versionResult.exitCode}:${versionResult.stdout.trim().take(120)}"
                }

            val diagnostics = "binary=[$ytDlpPath] version=[$version]"
            cachedDiagnostics = diagnostics
            diagnostics
        }
    }

    suspend fun downloadTrack(query: String, maxFileSizeMb: Int = 45): YtDlpResult = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Query must not be blank" }
        require(maxFileSizeMb in 1..50) { "maxFileSizeMb must be between 1 and 50" }

        val workDir = Files.createTempDirectory("ytdlp-")

        try {
            runDownload(workDir, query, maxFileSizeMb)
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private suspend fun runDownload(workDir: Path, query: String, maxFileSizeMb: Int): YtDlpResult {
        val diagnostics = getRuntimeDiagnostics()

        log.info { "yt-dlp track download start query=[${query.take(120)}] maxFileSizeMb=$maxFileSizeMb $diagnostics ${authDiagnostics()}" }

        val candidates = searchCandidates(query)

        log.info { "yt-dlp search returned ${candidates.size} candidate(s) for query=[${query.take(120)}]" }

        if (candidates.isEmpty()) {
            log.warn { "yt-dlp found no YouTube candidates for query=[${query.take(120)}]" }
            return YtDlpResult.NotFound
        }

        val retryableFailures = mutableListOf<YtDlpResult.Failure>()

        candidates.forEachIndexed { index, candidate ->
            val label = "candidate ${index + 1}/${candidates.size}"
            log.info { "yt-dlp trying $label url=[${candidate.url}] query=[${query.take(120)}]" }

            val attemptDir = Files.createDirectory(workDir.resolve("candidate-$index"))
            val attempt = downloadCandidate(attemptDir, candidate.url, query, maxFileSizeMb)

            when (val result = attempt.result) {
                is YtDlpResult.Track -> {
                    log.info { "yt-dlp $label succeeded title=[${result.value.title}] performer=[${result.value.performer}]" }
                    return result
                }

                is YtDlpResult.AuthRequired -> {
                    log.warn { "yt-dlp $label requires auth url=[${candidate.url}] ${authDiagnostics()}" }
                    return result
                }

                is YtDlpResult.TooLarge -> {
                    log.warn { "yt-dlp $label is too large url=[${candidate.url}] sizeBytes=${result.sizeBytes}" }
                    return result
                }

                is YtDlpResult.NotFound -> {
                    log.info { "yt-dlp $label was not usable url=[${candidate.url}], trying next candidate" }
                }

                is YtDlpResult.Failure -> {
                    log.warn { "yt-dlp $label failed retryable=${attempt.retryable} url=[${candidate.url}]: ${result.reason.take(300)}" }
                    if (!attempt.retryable) return result
                    retryableFailures += result
                }
            }
        }

        val allFormatUnavailable =
            retryableFailures.size == candidates.size &&
                    retryableFailures.all { it.reason.contains(FORMAT_UNAVAILABLE_MARKER, ignoreCase = true) }

        if (allFormatUnavailable) {
            return YtDlpResult.Failure("yt-dlp found no available formats for any of ${candidates.size} YouTube candidates on this server. Check the server yt-dlp version and YouTube cookies.")
        }

        return retryableFailures.lastOrNull() ?: YtDlpResult.NotFound
    }

    private suspend fun searchCandidates(query: String): List<YtDlpSearchCandidate> {
        val command =
            buildList {
                add(ytDlpPath)
                add("--ignore-config")
                add("--no-warnings")
                addAll(listOf("--dump-single-json", "--flat-playlist", "--playlist-end", SEARCH_RESULT_LIMIT.toString()))
                addAll(authArgs())
                add("ytsearch$SEARCH_RESULT_LIMIT:$query")
            }

        val result = runCommand(command)

        if (result.timedOut) {
            log.warn { "yt-dlp search timed out after ${timeoutSeconds}s for query=[${query.take(120)}] ${authDiagnostics()}" }
            return emptyList()
        }

        if (result.exitCode != 0) {
            val combined = result.stderr.ifBlank { result.stdout }
            log.warn { "yt-dlp search exit ${result.exitCode} for query=[${query.take(120)}] ${authDiagnostics()}: ${combined.take(500)}" }
            return emptyList()
        }

        val candidates = parseSearchCandidates(result.stdout)

        log.info { "yt-dlp search parsed ${candidates.size} candidate URL(s) for query=[${query.take(120)}]" }

        return candidates
    }

    private suspend fun downloadCandidate(workDir: Path, url: String, query: String, maxFileSizeMb: Int): DownloadAttempt {
        val outputTemplate = workDir.resolve("audio.%(ext)s").toString()

        val command =
            buildList {
                add(ytDlpPath)
                add("--ignore-config")
                addAll(listOf("-x", "--audio-format", "m4a"))
                addAll(listOf("--format", "bestaudio/best"))
                addAll(listOf("--no-playlist", "--no-warnings"))
                addAll(listOf("--max-filesize", "${maxFileSizeMb}M"))
                add("--print-json")
                addAll(authArgs())
                addAll(listOf("-o", outputTemplate))
                add(url)
            }

        val commandResult = runCommand(command)

        if (commandResult.timedOut) {
            log.warn { "yt-dlp download timed out after ${timeoutSeconds}s url=[$url] query=[${query.take(120)}]" }
            return DownloadAttempt(YtDlpResult.Failure("yt-dlp timed out after ${timeoutSeconds}s"))
        }

        if (commandResult.exitCode != 0) {
            return classifyDownloadError(commandResult, url, query, maxFileSizeMb)
        }

        val info = parseInfoJson(commandResult.stdout) ?: return DownloadAttempt(YtDlpResult.Failure("yt-dlp produced no metadata"))
        val audioFile = workDir.resolve("audio.m4a")

        if (!Files.exists(audioFile)) {
            return DownloadAttempt(YtDlpResult.Failure("yt-dlp finished but no audio file at $audioFile"))
        }

        val bytes = Files.readAllBytes(audioFile)

        if (bytes.size > maxFileSizeMb * 1024 * 1024) {
            log.warn { "yt-dlp downloaded file exceeds Telegram limit url=[$url] bytes=${bytes.size} maxFileSizeMb=$maxFileSizeMb" }
            return DownloadAttempt(YtDlpResult.TooLarge(sizeBytes = bytes.size.toLong()))
        }

        return DownloadAttempt(
            YtDlpResult.Track(
                YtDlpTrack(
                    bytes = bytes,
                    title = info.track ?: info.title ?: "Unknown",
                    performer = info.artist ?: info.uploader ?: info.channel ?: "Unknown",
                    durationSeconds = info.duration?.toInt(),
                    sourceUrl = info.webpageUrl
                )
            )
        )
    }

    private suspend fun classifyDownloadError(commandResult: YtDlpCommandResult, url: String, query: String, maxFileSizeMb: Int): DownloadAttempt {
        val combined = commandResult.stderr.ifBlank { commandResult.stdout }

        return when {
            combined.containsAny("File is larger than max-filesize") -> {
                log.warn { "yt-dlp download rejected by max-filesize url=[$url] maxFileSizeMb=$maxFileSizeMb" }
                DownloadAttempt(YtDlpResult.TooLarge(sizeBytes = maxFileSizeMb.toLong() * 1024 * 1024))
            }

            combined.containsAny("Sign in to confirm you", "confirm your age", "cookies-from-browser") -> {
                log.warn { "yt-dlp download requires auth url=[$url] query=[${query.take(120)}] ${authDiagnostics()}: ${combined.take(500)}" }
                DownloadAttempt(YtDlpResult.AuthRequired)
            }

            combined.containsAny("No video results", "Unable to extract") -> {
                log.warn { "yt-dlp download could not extract candidate url=[$url] query=[${query.take(120)}]: ${combined.take(500)}" }
                DownloadAttempt(YtDlpResult.NotFound, retryable = true)
            }

            else -> {
                val retryable = combined.contains(FORMAT_UNAVAILABLE_MARKER, ignoreCase = true)
                log.warn { "yt-dlp download exit ${commandResult.exitCode} retryable=$retryable query=[${query.take(120)}] url=[$url]: ${combined.take(500)}" }
                if (retryable) logUnavailableFormatsDiagnostics(url, query)
                DownloadAttempt(YtDlpResult.Failure("yt-dlp exit ${commandResult.exitCode}: ${combined.take(200)}"), retryable = retryable)
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
                val stdout = runCatching { withTimeout(1.seconds) { outputDeferred.await() } }.getOrDefault("")
                YtDlpCommandResult(stdout = stdout, stderr = "", exitCode = -1, timedOut = true)
            } else {
                val stdout = runCatching { withTimeout(5.seconds) { outputDeferred.await() } }.getOrDefault("")
                YtDlpCommandResult(stdout = stdout, stderr = "", exitCode = process.exitValue())
            }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun parseInfoJson(stdout: String): YtDlpInfo? {
        val firstLine = stdout.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: return null
        return runCatching { json.decodeFromString<YtDlpInfo>(firstLine) }.getOrNull()
    }

    private fun parseSearchCandidates(stdout: String): List<YtDlpSearchCandidate> {
        val firstLine = stdout.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: return emptyList()
        val search = runCatching { json.decodeFromString<YtDlpSearchResult>(firstLine) }.getOrNull() ?: return emptyList()

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
                add(ytDlpPath)
                add("--ignore-config")
                add("--no-warnings")
                add("--list-formats")
                addAll(authArgs())
                add(url)
            }

        log.info { "yt-dlp list-formats start url=[$url] query=[${query.take(120)}] ${authDiagnostics()}" }

        val result = runCommand(command)
        val output = result.stderr.ifBlank { result.stdout }.trim()

        when {
            result.timedOut -> log.warn { "yt-dlp list-formats timed out url=[$url] query=[${query.take(120)}]" }
            result.exitCode != 0 -> log.warn {
                "yt-dlp list-formats exit ${result.exitCode} url=[$url] query=[${query.take(120)}]: ${output.take(2000)}"
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
            val sizeBytes = if (exists) runCatching { Files.size(path).toString() }.getOrDefault("unknown") else "missing"

            "auth=cookies-file path=[$file] exists=$exists readable=$readable sizeBytes=$sizeBytes"
        }.getOrElse { error ->
            "auth=cookies-file path=[$file] invalidPath=[${error.message}]"
        }
}

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any { contains(it, ignoreCase = true) }
