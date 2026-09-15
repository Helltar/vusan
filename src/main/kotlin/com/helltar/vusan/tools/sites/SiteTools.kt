package com.helltar.vusan.tools.sites

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import com.helltar.vusan.tools.sandbox.PublishedSite
import com.helltar.vusan.tools.sandbox.SandboxClient
import kotlin.time.Clock
import kotlin.time.Instant

private const val MAX_PATH_CHARS = 400
private const val INDEX = "index.html"

/**
 * Putting what someone built in their sandbox on the public internet.
 *
 * Publishing is a snapshot the Regolith server takes of one directory: the files leave the sandbox
 * at that moment and the site stays as it was, whatever happens in the sandbox afterwards.
 */
@Suppress("unused")
class SiteTools(
    // the turn's own handle: the files come from the sandbox its commands ran in
    private val sandbox: SandboxClient.PersonSandbox,
) : ToolSet {

    @Tool
    @LLMDescription(SiteToolDescriptions.PUBLISH_SITE)
    suspend fun publishSite(
        @LLMDescription(SiteToolDescriptions.DIRECTORY)
        directory: String,
    ): String = suspendToolGuard {
        val path = directory.requireToolText("Directory", MAX_PATH_CHARS)
        val warning = missingIndex(path)
        val site = sandbox.publishSite(path)
        listOfNotNull(describePublished(site), warning).joinToString("\n")
    }

    @Tool
    @LLMDescription(SiteToolDescriptions.SITE_STATUS)
    suspend fun siteStatus(): String = suspendToolGuard {
        val site = sandbox.publishedSite()
            ?: return@suspendToolGuard "Nothing is published. Build the files in the sandbox, then publish that directory."
        buildString {
            append("Published at ${site.url} — ${site.files} file(s), ${site.bytes.asMegabytes()}")
            append(", last published ${site.publishedAt.asAgeDescription()}")
            append(".\nThis is what the site holds right now; the sandbox may have moved on since.")
        }
    }

    @Tool
    @LLMDescription(SiteToolDescriptions.UNPUBLISH_SITE)
    suspend fun unpublishSite(): String = suspendToolGuard {
        if (sandbox.unpublishSite()) {
            "The site is offline and its address returns nothing. The sandbox files were kept."
        } else {
            "There was nothing published to take down."
        }
    }

    /**
     * The one mistake that cannot be seen from the result: a directory with no `index.html` at its top
     * publishes fine and its link then opens nothing.
     */
    private suspend fun missingIndex(path: String): String? = runCatching {
        if (INDEX in sandbox.entries(path)) {
            null
        } else {
            "There is no `$INDEX` at the top of `$path`, so the link itself will show nothing. " +
                "Publish the directory that holds the page, not the one above it."
        }
    }.getOrElse {
        it.rethrowIfCancellation()
        null
    }
}

private fun describePublished(site: PublishedSite): String =
    "Published at ${site.url} — ${site.files} file(s), ${site.bytes.asMegabytes()}. Give the user that link."

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
