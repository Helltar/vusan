package com.helltar.vusan.tools.sites

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import com.helltar.vusan.tools.workspace.WorkspaceClient
import java.time.Duration
import java.time.Instant

private const val MAX_PATH_CHARS = 400
private const val MAX_ARCHIVE_BYTES = 50 * 1024 * 1024

@Suppress("unused")
class SiteTools(
    private val client: SiteClient,
    private val workspace: WorkspaceClient,
    // one key for both services: the workspace the archive is read from and the site it is published to
    // belong to the same person, and neither is scoped to a chat.
    private val personKey: String
) : ToolSet {

    @Tool
    @LLMDescription(SiteToolDescriptions.PUBLISH_SITE)
    suspend fun publishSite(
        @LLMDescription(SiteToolDescriptions.ARCHIVE_PATH)
        archivePath: String
    ): String = suspendToolGuard {
        val path = archivePath.requireToolText("Archive path", MAX_PATH_CHARS)
        val archive = workspace.readFile(personKey, path, MAX_ARCHIVE_BYTES)
        require(archive.isNotEmpty()) { "`$path` is empty" }

        val upload = client.startUpload(personKey)
        var record: SiteRecord? = null
        try {
            val summary = readSiteArchive(archive, upload.limits) { file, bytes ->
                client.upload(upload.uploadId, file, bytes)
            }
            record = client.commit(upload.uploadId)
            describePublished(record, summary)
        } finally {
            // an abandoned staging directory expires on its own, but leaving one behind means the next
            // publish starts against a service still holding the last failure's files.
            if (record == null) {
                runCatching { client.discard(upload.uploadId) }.onFailure { it.rethrowIfCancellation() }
            }
        }
    }

    @Tool
    @LLMDescription(SiteToolDescriptions.SITE_STATUS)
    suspend fun siteStatus(): String = suspendToolGuard {
        val status = client.status(personKey)
        if (!status.published || status.url == null) {
            "Nothing is published. Build the files in the workspace, zip them and use publishSite."
        } else {
            describeStatus(status)
        }
    }

    @Tool
    @LLMDescription(SiteToolDescriptions.UNPUBLISH_SITE)
    suspend fun unpublishSite(): String = suspendToolGuard {
        if (client.remove(personKey)) {
            "The site is offline and its address returns nothing. The workspace files were kept."
        } else {
            "There was nothing published to take down."
        }
    }
}

private fun describeStatus(status: SiteStatus): String = buildString {
    appendLine(
        "Published at ${status.url} — ${status.files} file(s), ${status.bytes.asMegabytes()}, " +
            "last changed ${status.updatedAt.asAgeDescription()}."
    )
    if (status.listing.isNotEmpty()) {
        appendLine(xmlBlock("site_files", status.listing.joinToString("\n") { "${it.path} (${it.bytes.asMegabytes()})" }))
        if (status.truncated) appendLine("Only the first ${status.listing.size} files are listed.")
    }
    append("This is what the site holds right now; the workspace may have moved on since it was published.")
}

private fun describePublished(record: SiteRecord, summary: SiteArchiveSummary): String = buildString {
    append("Published at ${record.url} — ${summary.files} file(s), ${summary.bytes.asMegabytes()}. ")
    append("Give the user that link.")
    if (!summary.hasRootIndex) {
        append(
            "\nThere is no `index.html` at the top of the archive, so the link itself will show nothing. " +
                "Zip the contents of the site directory rather than the directory, and publish again."
        )
    }
}

private fun Long.asMegabytes(): String =
    if (this < 1024 * 1024) "${(this + 1023) / 1024} KB" else "%.1f MB".format(this / (1024.0 * 1024.0))

private fun Long.asAgeDescription(): String {
    if (this <= 0) return "at an unknown time"
    val elapsed = Duration.between(Instant.ofEpochMilli(this), Instant.now())
    return when {
        elapsed.isNegative || elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()} minute(s) ago"
        elapsed.toDays() < 1 -> "${elapsed.toHours()} hour(s) ago"
        else -> "${elapsed.toDays()} day(s) ago"
    }
}
