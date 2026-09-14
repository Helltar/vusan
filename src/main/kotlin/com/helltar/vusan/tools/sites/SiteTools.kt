package com.helltar.vusan.tools.sites

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import com.helltar.vusan.tools.workspace.PublishedSite
import com.helltar.vusan.tools.workspace.WorkspaceClient
import java.time.Duration
import java.time.Instant

private const val MAX_PATH_CHARS = 400
private const val INDEX = "index.html"

/**
 * Putting what someone built in their workspace on the public internet.
 *
 * Publishing is a snapshot the workspace server takes of one directory: the files leave the workspace
 * at that moment and the site stays as it was, whatever happens in the workspace afterwards.
 */
@Suppress("unused")
class SiteTools(
    private val workspace: WorkspaceClient,
    // the workspace the files come from and the site they are served at belong to the same person
    private val personKey: String
) : ToolSet {

    @Tool
    @LLMDescription(SiteToolDescriptions.PUBLISH_SITE)
    suspend fun publishSite(
        @LLMDescription(SiteToolDescriptions.DIRECTORY)
        directory: String
    ): String = suspendToolGuard {
        val path = directory.requireToolText("Directory", MAX_PATH_CHARS)
        val warning = missingIndex(path)
        val site = workspace.publishSite(personKey, path)
        listOfNotNull(describePublished(site), warning).joinToString("\n")
    }

    @Tool
    @LLMDescription(SiteToolDescriptions.SITE_STATUS)
    suspend fun siteStatus(): String = suspendToolGuard {
        val site = workspace.publishedSite(personKey)
            ?: return@suspendToolGuard "Nothing is published. Build the files in the workspace, then publish that directory."
        buildString {
            append("Published at ${site.url} — ${site.files} file(s), ${site.bytes.asMegabytes()}")
            site.publishedAt?.let { append(", last published ${it.asAgeDescription()}") }
            append(".\nThis is what the site holds right now; the workspace may have moved on since.")
        }
    }

    @Tool
    @LLMDescription(SiteToolDescriptions.UNPUBLISH_SITE)
    suspend fun unpublishSite(): String = suspendToolGuard {
        if (workspace.unpublishSite(personKey)) {
            "The site is offline and its address returns nothing. The workspace files were kept."
        } else {
            "There was nothing published to take down."
        }
    }

    /**
     * The one mistake that cannot be seen from the result: a directory with no `index.html` at its top
     * publishes fine and its link then opens nothing.
     */
    private suspend fun missingIndex(path: String): String? = runCatching {
        if (INDEX in workspace.entries(personKey, path)) {
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

private fun String.asAgeDescription(): String {
    val at = runCatching { Instant.parse(this) }.getOrNull() ?: return "at an unknown time"
    val elapsed = Duration.between(at, Instant.now())

    return when {
        elapsed.isNegative || elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()} minute(s) ago"
        elapsed.toDays() < 1 -> "${elapsed.toHours()} hour(s) ago"
        else -> "${elapsed.toDays()} day(s) ago"
    }
}
