package com.helltar.vusan.tools.sites

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import com.helltar.vusan.tools.sandbox.PublishedSite
import com.helltar.vusan.tools.sandbox.SandboxClient
import com.helltar.vusan.tools.sandbox.sandboxPath
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Clock
import kotlin.time.Instant

private const val MAX_PATH_CHARS = 400

/**
 * Puts what someone built in their sandbox on the public internet. Publishing is a snapshot the Regolith
 * server takes of one directory; the site stays as it was whatever the sandbox does afterward.
 */
class SiteTools(
    // the turn's own handle: files come from the sandbox its commands ran in
    private val sandbox: SandboxClient.PersonSandbox,
) : ToolSet {

    @Tool(SiteToolDescriptions.PUBLISH_SITE)
    suspend fun publishSite(
        @Arg(SiteToolDescriptions.DIRECTORY)
        directory: String,
    ): String = suspendToolGuard {
        val path = directory.requireToolText("Directory", MAX_PATH_CHARS).sandboxPath()
        val site = sandbox.publishSite(path)
        listOfNotNull(describePublished(site), missingIndex(site, path)).joinToString("\n")
    }

    @Tool(SiteToolDescriptions.SITE_STATUS, readOnly = true)
    suspend fun siteStatus(): String = suspendToolGuard {
        val site = sandbox.publishedSite()
            ?: return@suspendToolGuard "Nothing is published. Build the files in the sandbox, then publish that directory."
        buildString {
            append("Published at ${site.url} — ${site.files} file(s), ${site.bytes.asMegabytes()}")
            append(", last published ${site.publishedAt.asAgeDescription()}, online until ${site.until.asDate()}")
            append(".\nThis is what the site holds right now; the sandbox may have moved on since.")
            if (sandbox.homeReleased()) {
                append(
                    "\nThe sandbox files it was built from were deleted after the sandbox went unused; " +
                        "the site itself is unchanged. To change it, build it again first; `setSiteOnlineUntil` still moves its date.",
                )
            }
        }
    }

    @Tool(SiteToolDescriptions.SET_SITE_UNTIL)
    suspend fun setSiteOnlineUntil(
        @Arg(SiteToolDescriptions.UNTIL_DATE)
        date: String,
    ): String = suspendToolGuard {
        val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
        requireNotNull(day) { "Give the date as `YYYY-MM-DD`, for example `2026-12-31`" }
        require(!day.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            "$day has passed; to take the site down now, use `unpublishSite`"
        }
        // the site stays up through the whole day the person named, which is the date every answer shows
        val site = sandbox.setSiteUntil(Instant.parse("${day}T23:59:59Z"))
            ?: return@suspendToolGuard "Nothing is published, so there is no site to keep online."
        "The site at ${site.url} now stays online until ${site.until.asDate()}; after that it is gone, and the next publish gets a new address."
    }

    @Tool(SiteToolDescriptions.UNPUBLISH_SITE)
    suspend fun unpublishSite(): String = suspendToolGuard {
        if (sandbox.unpublishSite()) {
            "The site is offline and its address returns nothing. The sandbox files were kept; publishing again gives a new address."
        } else {
            "There was nothing published to take down."
        }
    }

    /**
     * The one mistake the numbers cannot show: a directory without a top-level `index.html` publishes fine
     * and its link opens nothing. The server saw the snapshot, so it says.
     */
    private fun missingIndex(site: PublishedSite, path: String): String? =
        if (site.hasIndex) {
            null
        } else {
            "There is no `index.html` at the top of `$path`, so the link itself will show nothing. " +
                "Publish the directory that holds the page, not the one above it."
        }
}

private fun describePublished(site: PublishedSite): String =
    "Published at ${site.url} — ${site.files} file(s), ${site.bytes.asMegabytes()}, online until ${site.until.asDate()}. " +
        "Give the user that link."

// a person reads a term as a day; the hour is the server's business
private fun Instant.asDate(): String = toString().substringBefore('T')

private fun Long.asMegabytes(): String =
    if (this < 1024 * 1024) "${(this + 1023) / 1024} KB" else "%.1f MB".format(this / (1024.0 * 1024.0))

private fun Instant.asAgeDescription(): String {
    val elapsed = Clock.System.now() - this

    return when {
        elapsed.isNegative() || elapsed.inWholeMinutes < 1 -> "just now"
        elapsed.inWholeHours < 1 -> "${elapsed.inWholeMinutes} minute(s) ago"
        elapsed.inWholeDays < 1 -> "${elapsed.inWholeHours} hour(s) ago"
        else -> "${elapsed.inWholeDays} day(s) ago"
    }
}
