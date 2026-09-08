package com.helltar.vusan.tools.sites

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

private const val MAX_ENTRY_PATH_CHARS = 400
private const val MAX_SEGMENT_CHARS = 120
private const val COPY_CHUNK = 64 * 1024

internal class SiteArchiveSummary(val files: Int, val bytes: Long, val hasRootIndex: Boolean)

/**
 * Reads a zip the model built in its workspace and hands out one file at a time, so nothing larger than
 * a single entry is ever held. Every path is checked here and again by the service: this side fails
 * before uploading anything, that side because it does not trust its caller.
 *
 * A zip can carry a symlink entry, but nothing here writes to a filesystem — an entry like that becomes
 * an ordinary file holding the link target, which is harmless. The caps are what a compression bomb
 * runs into.
 */
internal suspend fun readSiteArchive(
    archive: ByteArray,
    limits: SiteLimits,
    emit: suspend (path: String, bytes: ByteArray) -> Unit
): SiteArchiveSummary {
    val seen = mutableSetOf<String>()
    var total = 0L

    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val path = sitePath(entry.name, limits.pathDepth)
            require(seen.add(path)) { "The archive holds `$path` twice" }
            require(seen.size <= limits.files) { "A site may hold at most ${limits.files} files" }

            val remaining = limits.totalBytes - total
            val bytes = zip.readCapped(minOf(limits.fileBytes, remaining)) {
                if (remaining < limits.fileBytes) {
                    "The site is larger than the ${limits.totalBytes / (1024 * 1024)} MB limit"
                } else {
                    "`$path` is larger than the ${limits.fileBytes / (1024 * 1024)} MB per-file limit"
                }
            }
            total += bytes.size
            emit(path, bytes)
        }
    }

    require(seen.isNotEmpty()) { "The archive holds no files" }
    return SiteArchiveSummary(seen.size, total, "index.html" in seen)
}

/**
 * The path an entry is published at. Dot segments are refused rather than dropped: `.git` and `.env`
 * land in a build directory far more often than anyone means to publish them.
 */
internal fun sitePath(raw: String, maxDepth: Int): String {
    require(raw.length <= MAX_ENTRY_PATH_CHARS) { "A path in the archive is too long" }
    require(!raw.startsWith("/") && '\\' !in raw && raw.none { it.isControlCharacter() }) {
        "`$raw` is not a usable path inside a site"
    }
    val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
    require(parts.isNotEmpty()) { "`$raw` does not name a file" }
    require(parts.size <= maxDepth) { "`$raw` is deeper than the $maxDepth levels a site allows" }
    parts.forEach { part ->
        require(part != "..") { "`$raw` points outside the site" }
        require(!part.startsWith(".")) { "`$raw` is a dotfile; those are never published" }
        require(part.length <= MAX_SEGMENT_CHARS) { "A name in `$raw` is too long" }
    }
    return parts.joinToString("/")
}

private fun Char.isControlCharacter(): Boolean = code < 0x20 || code == 0x7f

private inline fun ZipInputStream.readCapped(cap: Long, message: () -> String): ByteArray {
    require(cap > 0) { message() }
    val output = ByteArrayOutputStream(COPY_CHUNK)
    val chunk = ByteArray(COPY_CHUNK)
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        require(output.size() + read <= cap) { message() }
        output.write(chunk, 0, read)
    }
    return output.toByteArray()
}
