package com.helltar.vusan.tools.youtube

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// stderr is merged into stdout (`redirectErrorStream(true)`), so one stream carries everything.
data class YtDlpCommandResult(
    val stdout: String,
    val exitCode: Int,
    val timedOut: Boolean = false
)

internal data class YtDlpSearchCandidate(val url: String)

/**
 * Shared yt-dlp invocation surface: process handling, the auth and extractor arguments every
 * command needs, and turning a user query into candidate video URLs. Owned by the feature clients
 * ([YtDlpClient], [YouTubeTranscriptClient]) so they only assemble the arguments specific to them.
 */
class YtDlpRunner(
    private val cookiesFile: String? = null,
    val timeoutSeconds: Long = 180
) {

    private companion object {
        const val SEARCH_RESULT_LIMIT = 5
        val json = Json { ignoreUnknownKeys = true }
        val log = KotlinLogging.logger {}
    }

    private val diagnosticsMutex = Mutex()
    private var cachedDiagnostics: String? = null

    suspend fun runCommand(command: List<String>): YtDlpCommandResult = withContext(Dispatchers.IO) {
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

    suspend fun runtimeDiagnostics(): String = diagnosticsMutex.withLock {
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

    fun authArgs(): List<String> =
        cookiesFile?.takeUnless { it.isBlank() }?.let { listOf("--cookies", it) }
            ?: emptyList()

    fun youtubeArgs(): List<String> =
        listOf("--remote-components", "ejs:github", "--user-agent", USER_AGENT)

    fun authDiagnostics(): String {
        cookiesFile?.takeUnless { it.isBlank() }?.let { return cookieFileDiagnostics(it) }
        return "auth=none"
    }

    /** Resolves a direct URL as itself, and anything else through a YouTube search. */
    internal suspend fun videoCandidates(query: String): List<YtDlpSearchCandidate> {
        val trimmed = query.trim()

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return listOf(YtDlpSearchCandidate(trimmed))
        }

        return searchCandidates(query)
    }

    internal suspend fun searchCandidates(query: String): List<YtDlpSearchCandidate> {
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
                addAll(youtubeArgs())
                add("--")
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

    internal fun parseInfoJson(stdout: String): YtDlpInfo? {
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

    private suspend fun Deferred<String>.awaitWithin(timeout: Duration): String =
        try {
            withTimeout(timeout) { await() }
        } catch (_: TimeoutCancellationException) {
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
}

internal const val YT_DLP_BINARY = "yt-dlp"
internal const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"

internal fun String.containsAny(vararg needles: String): Boolean =
    needles.any { contains(it, ignoreCase = true) }
