package com.helltar.vusan.tools.youtube

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class YouTubeTranscriptClient(private val runner: YtDlpRunner) {

    private companion object {
        const val SUBTITLE_FILE_PREFIX = "subs"
        const val MAX_SUBTITLE_LANGUAGE_ATTEMPTS = 3

        // fallback only, for videos that declare no language of their own: the widest-spoken
        // languages on youtube, so the most likely original track is tried before a translation.
        val SUBTITLE_LANGS = listOf("en", "es", "pt", "hi", "ru", "ja", "de", "fr", "ko", "uk")
        val log = KotlinLogging.logger {}
    }

    private data class SubtitleFile(val path: Path, val language: String)

    suspend fun fetchTranscript(query: String): YtDlpResult<YtDlpTranscript> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "Query must not be blank" }

            val workDir = Files.createTempDirectory("ytdlp-subs-")

            try {
                runTranscriptFetch(workDir, query)
            } finally {
                workDir.toFile().deleteRecursively()
            }
        }

    private suspend fun runTranscriptFetch(workDir: Path, query: String): YtDlpResult<YtDlpTranscript> {
        val diagnostics = runner.runtimeDiagnostics()

        log.info {
            "yt-dlp transcript start query=[${query.take(120)}] $diagnostics ${runner.authDiagnostics()}"
        }

        val candidates = runner.videoCandidates(query)

        if (candidates.isEmpty()) {
            log.warn { "yt-dlp found no YouTube candidates for query=[${query.take(120)}]" }
            return YtDlpResult.NotFound
        }

        var lastFailure: YtDlpResult.Failure? = null

        candidates.forEachIndexed { index, candidate ->
            val label = "transcript candidate ${index + 1}/${candidates.size}"
            log.info { "yt-dlp trying $label url=[${candidate.url}] query=[${query.take(120)}]" }

            val attemptDir = Files.createDirectory(workDir.resolve("candidate-$index"))

            when (val result = fetchTranscriptCandidate(attemptDir, candidate.url, query)) {
                is YtDlpResult.Success -> {
                    log.info {
                        "yt-dlp $label succeeded title=[${result.value.title}] lang=[${result.value.language}] " +
                                "chars=${result.value.text.length}"
                    }

                    return result
                }

                is YtDlpResult.AuthRequired -> {
                    log.warn { "yt-dlp $label requires auth url=[${candidate.url}] ${runner.authDiagnostics()}" }
                    return result
                }

                is YtDlpResult.Failure -> {
                    log.warn { "yt-dlp $label failed url=[${candidate.url}]: ${result.reason.take(300)}" }
                    lastFailure = result
                }

                else -> log.info { "yt-dlp $label has no usable subtitles url=[${candidate.url}]" }
            }
        }

        return lastFailure ?: YtDlpResult.NotFound
    }

    private suspend fun fetchTranscriptCandidate(
        workDir: Path,
        url: String,
        query: String
    ): YtDlpResult<YtDlpTranscript> {
        val info =
            when (val result = probeVideoInfo(url, query)) {
                is YtDlpResult.Success -> result.value
                is YtDlpResult.AuthRequired -> return result
                is YtDlpResult.Failure -> return result
                is YtDlpResult.NotFound -> return result
                is YtDlpResult.TooLarge -> return result
            }

        val languages = subtitleLanguageCandidates(info)

        if (languages.isEmpty()) {
            log.info { "yt-dlp transcript has no offered subtitle language url=[$url]" }
            return YtDlpResult.NotFound
        }

        languages.forEachIndexed { index, language ->
            // the language comes from yt-dlp's own json, but it still ends up in a path, so the
            // attempt directory is numbered rather than named after it.
            val attemptDir = Files.createDirectory(workDir.resolve("lang-$index"))
            val subtitle = downloadSubtitle(attemptDir, url, language)

            if (subtitle == null) {
                log.info { "yt-dlp transcript lang=[$language] unavailable url=[$url], trying next language" }
                return@forEachIndexed
            }

            val text = withContext(Dispatchers.IO) { parseWebVtt(Files.readString(subtitle.path)) }

            if (text.isBlank()) {
                log.info { "yt-dlp transcript lang=[$language] parsed to nothing url=[$url]" }
                return@forEachIndexed
            }

            return YtDlpResult.Success(
                YtDlpTranscript(
                    text = text,
                    title = info.title ?: "Unknown",
                    uploader = info.uploader ?: info.channel,
                    durationSeconds = info.duration?.toInt(),
                    language = subtitle.language,
                    autoGenerated = info.subtitles?.keys?.matchLanguage(subtitle.language) == null,
                    sourceUrl = info.webpageUrl
                )
            )
        }

        return YtDlpResult.NotFound
    }

    /** Reads video metadata without touching any media or caption stream. */
    private suspend fun probeVideoInfo(url: String, query: String): YtDlpResult<YtDlpInfo> {
        val command =
            buildList {
                add(YT_DLP_BINARY)
                add("--ignore-config")
                addAll(listOf("--skip-download", "--dump-json"))
                addAll(listOf("--no-playlist", "--no-warnings"))
                addAll(runner.authArgs())
                addAll(runner.youtubeArgs())
                add("--")
                add(url)
            }

        val commandResult = runner.runCommand(command)

        if (commandResult.timedOut) {
            log.warn {
                "yt-dlp info timed out after ${runner.timeoutSeconds}s url=[$url] query=[${query.take(120)}]"
            }

            return YtDlpResult.Failure("yt-dlp timed out after ${runner.timeoutSeconds}s")
        }

        val output = commandResult.stdout

        if (output.containsAny("Sign in to confirm you", "confirm your age", "cookies-from-browser")) {
            log.warn { "yt-dlp info requires auth url=[$url] ${runner.authDiagnostics()}: ${output.take(500)}" }
            return YtDlpResult.AuthRequired
        }

        if (commandResult.exitCode != 0) {
            log.warn { "yt-dlp info exit ${commandResult.exitCode} url=[$url]: ${output.take(500)}" }
            return YtDlpResult.Failure("yt-dlp exit ${commandResult.exitCode}: ${output.take(200)}")
        }

        return runner.parseInfoJson(output)
            ?.let { YtDlpResult.Success(it) }
            ?: YtDlpResult.Failure("yt-dlp produced no metadata")
    }

    /**
     * Picks which subtitle tracks are worth downloading, best first.
     *
     * The video's own language wins: YouTube's other tracks are machine translations of it, and a
     * translated transcript is strictly worse input than the original — the model reading it
     * understands every language here and translates for the user at the end anyway.
     * [SUBTITLE_LANGS] only decides which translation to settle for when the original is missing.
     *
     * Selecting up front matters because each language is a separate caption request that YouTube
     * rate-limits: asking for a list would download a translation per entry, since auto-captions
     * are offered in ~900 languages for every video.
     */
    private fun subtitleLanguageCandidates(info: YtDlpInfo): List<String> {
        val offered = info.subtitles.orEmpty().keys + info.automaticCaptions.orEmpty().keys

        return (listOfNotNull(info.language) + SUBTITLE_LANGS)
            .mapNotNull { offered.matchLanguage(it) }
            .distinct()
            .take(MAX_SUBTITLE_LANGUAGE_ATTEMPTS)
    }

    private suspend fun downloadSubtitle(workDir: Path, url: String, language: String): SubtitleFile? {
        val command =
            buildList {
                add(YT_DLP_BINARY)
                add("--ignore-config")
                add("--skip-download")
                addAll(listOf("--write-subs", "--write-auto-subs"))
                addAll(listOf("--sub-langs", language))
                addAll(listOf("--sub-format", "vtt/best", "--convert-subs", "vtt"))
                addAll(listOf("--no-playlist", "--no-warnings"))
                addAll(runner.authArgs())
                addAll(runner.youtubeArgs())
                addAll(listOf("-o", workDir.resolve("$SUBTITLE_FILE_PREFIX.%(ext)s").toString()))
                add("--")
                add(url)
            }

        val commandResult = runner.runCommand(command)

        // a rate-limited caption request makes yt-dlp exit non-zero, so the file on disk decides
        // and the exit code only explains an empty directory.
        val subtitle = findSubtitleFile(workDir)

        if (subtitle == null && commandResult.exitCode != 0) {
            log.warn {
                "yt-dlp subtitle exit ${commandResult.exitCode} lang=[$language] url=[$url]: " +
                        commandResult.stdout.take(300)
            }
        }

        return subtitle
    }

    /** yt-dlp names subtitle files `<prefix>.<lang>.vtt`, so the language comes back from the filename. */
    private fun findSubtitleFile(workDir: Path): SubtitleFile? =
        runCatching {
            Files.list(workDir).use { paths ->
                paths.toList().firstNotNullOfOrNull { path ->
                    val name = path.fileName.toString()

                    name.removePrefix("$SUBTITLE_FILE_PREFIX.")
                        .removeSuffix(".vtt")
                        .takeIf { name.startsWith("$SUBTITLE_FILE_PREFIX.") && name.endsWith(".vtt") }
                        ?.let { SubtitleFile(path, it) }
                }
            }
        }.getOrNull()
}

/**
 * Resolves [language] against track names as yt-dlp reports them, returning the actual key.
 * YouTube tags tracks with regional and origin suffixes (`en-US`, `en-orig`), so an exact hit
 * is preferred and a base-language hit is accepted.
 */
private fun Collection<String>.matchLanguage(language: String): String? =
    firstOrNull { it.equals(language, ignoreCase = true) }
        ?: firstOrNull { it.substringBefore('-').equals(language.substringBefore('-'), ignoreCase = true) }
