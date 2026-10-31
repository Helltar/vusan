package com.helltar.vusan.llm.codex

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.helltar.vusan.common.rethrowIfCancellation

private val log = KotlinLogging.logger {}

// the Codex client version claimed to the backend. `/models` hides models whose `minimal_client_version` is
// newer, so an understated value makes the newest model look unavailable. startup claims the newest it finds
// (this floor, the installed CLI, the latest github release), so an upgraded host and a binary-less container
// both see new models without a vusan release; this constant only matters when github is out of reach.
private const val CODEX_CLIENT_VERSION_FLOOR = "0.162.0"
// where the CLI's own update check looks: the latest non-prerelease, tagged `rust-v<version>`
private const val CODEX_LATEST_RELEASE_URL = "https://api.github.com/repos/openai/codex/releases/latest"
private val CODEX_LATEST_RELEASE_TIMEOUT = 5.seconds
internal val CODEX_VERSION = Regex("""\d+\.\d+\.\d+""")

@Volatile
private var claimedClientVersion: String? = null

/**
 * Decides the client version this process claims, once at startup, and logs its source.
 *
 * [pinned] is `CODEX_CLIENT_VERSION` and wins outright, for a host that cannot reach github or a wrong
 * automatic choice. Otherwise the newest of the floor, the installed CLI and the latest release wins; a
 * silent source is skipped. Installed once rather than passed per call: the process has one Codex identity.
 */
suspend fun claimCodexClientVersion(
    http: HttpClient,
    pinned: String?,
    installed: () -> String? = { detectCodexClientVersion() },
) {
    if (pinned != null) {
        claimedClientVersion = pinned
        log.info { "Codex: client_version=[$pinned] pinned by CODEX_CLIENT_VERSION" }
        return
    }

    val candidates =
        listOf(
            "built-in floor" to CODEX_CLIENT_VERSION_FLOOR,
            "installed CLI" to installed(),
            "latest GitHub release" to fetchLatestCodexRelease(http),
        )

    // on a tie the earlier source keeps the credit, so the floor is named whenever it is current
    val (source, version) =
        candidates
            .mapNotNull { (name, found) -> found?.let { name to it } }
            .reduce { best, next -> if (next.second isNewerThan best.second) next else best }

    claimedClientVersion = version
    log.info {
        val others =
            candidates
                .filter { (name, _) -> name != source }
                .joinToString { (name, found) -> "$name=[${found ?: "none"}]" }

        "Codex: client_version=[$version] from the $source; $others"
    }
}

@Serializable
private data class GitHubRelease(@SerialName("tag_name") val tagName: String = "")

/** The latest Codex release on github, or `null` when unreadable: best effort, the other sources stand in. */
internal suspend fun fetchLatestCodexRelease(http: HttpClient): String? =
    runCatching {
        val release: GitHubRelease =
            http.get(CODEX_LATEST_RELEASE_URL) {
                timeout { requestTimeoutMillis = CODEX_LATEST_RELEASE_TIMEOUT.inWholeMilliseconds }
                header("User-Agent", codexUserAgent())
            }.body()

        requireNotNull(CODEX_VERSION.find(release.tagName)?.value) { "no version in tag_name=[${release.tagName}]" }
    }.onFailure { e ->
        e.rethrowIfCancellation()
        log.warn { "Codex: could not read the latest release from GitHub (${e.message}); claiming the client version without it" }
    }.getOrNull()

internal fun detectCodexClientVersion(
    command: List<String> = listOf("codex", "--version"),
    timeout: Duration = 5.seconds,
): String? =
    runCatching {
        require(command.isNotEmpty()) { "Codex version command must not be empty" }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()

        try {
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                return@runCatching null
            }

            CODEX_VERSION.find(process.inputStream.bufferedReader().use { it.readText() })?.value
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }.getOrNull()

internal fun codexClientVersion(): String = claimedClientVersion ?: CODEX_CLIENT_VERSION_FLOOR

/** Numeric `major.minor.patch` ordering, so `0.99.0` does not outrank `0.146.1` as strings would. */
internal infix fun String.isNewerThan(other: String): Boolean {
    val mine = versionParts()
    val theirs = other.versionParts()

    return mine.zip(theirs).firstOrNull { (left, right) -> left != right }?.let { (left, right) -> left > right }
        ?: (mine.size > theirs.size)
}

private fun String.versionParts(): List<Int> = split('.').map { it.toIntOrNull() ?: 0 }
