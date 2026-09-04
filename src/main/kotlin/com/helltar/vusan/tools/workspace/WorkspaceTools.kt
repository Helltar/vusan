package com.helltar.vusan.tools.workspace

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.util.Locale

private const val MAX_COMMAND_CHARS = 16_000
private const val MAX_CONTENT_CHARS = 400_000
private const val MAX_PATH_CHARS = 400
private const val MAX_STREAM_CHARS = 4_000
private const val MAX_SEND_FILES = 10
private const val MAX_MEDIA_GROUP = 10

// telegram serves a bot the files it stores only up to this size, so a larger attachment
// cannot be placed in the workspace at all
private const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

// what a bot may upload. a larger file is left in the workspace with a note rather than
// failing the send, since the model can usually shrink or split it.
private const val MAX_UPLOAD_BYTES = 50 * 1024 * 1024

private const val SLOW_RUN_MS = 1_000L

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm")

@Suppress("unused")
class WorkspaceTools(
    private val client: WorkspaceClient,
    private val context: RequestContext,
    private val outbox: BotOutbox,
    private val attachedFile: AttachedFile? = null
) : ToolSet {

    private val id = workspaceId(context)
    private var attachmentNote: String? = null
    private var attachmentHandled = false

    @Tool
    @LLMDescription(WorkspaceToolDescriptions.RUN_COMMAND)
    suspend fun runCommand(
        @LLMDescription(WorkspaceToolDescriptions.COMMAND)
        command: String,
        @LLMDescription(WorkspaceToolDescriptions.TIMEOUT_SECONDS)
        timeoutSeconds: Int = 0
    ): String = suspendToolGuard {
        val script = command.requireToolText("Command", MAX_COMMAND_CHARS)
        val note = placeAttachment()
        val result = client.exec(id, script, timeoutSeconds.takeIf { it > 0 })

        result.error?.let { return@suspendToolGuard it }

        buildString {
            note?.let { appendLine(it) }

            if (result.timedOut) {
                appendLine(
                    "The command hit the time limit and everything it started was killed. " +
                            "Run it again with a larger timeoutSeconds, or start it in the background " +
                            "with `setsid` and check on it with a later command."
                )
            }

            result.stdout.trim().takeIf { it.isNotEmpty() }
                ?.let { appendLine(xmlBlock("stdout", it.limitTo(MAX_STREAM_CHARS))) }

            result.stderr.trim().takeIf { it.isNotEmpty() }
                ?.let { appendLine(xmlBlock("stderr", it.limitTo(MAX_STREAM_CHARS))) }

            if (result.exitCode != 0 && !result.timedOut) appendLine("Exit code ${result.exitCode}.")

            if (result.stdoutTruncated || result.stderrTruncated) {
                appendLine(
                    "The output was cut short. The whole log is in the workspace at " +
                            "`${result.logPath}` — read the part you need with `grep` or `tail`."
                )
            }

            quotaWarning(result)?.let { appendLine(it) }

            result.elapsedMs.takeIf { it >= SLOW_RUN_MS }
                ?.let { appendLine("Took ${"%.1f".format(Locale.ROOT, it / 1000.0)}s.") }
        }.trim().ifBlank { "The command finished with no output." }
    }

    @Tool
    @LLMDescription(WorkspaceToolDescriptions.WRITE_FILE)
    suspend fun writeWorkspaceFile(
        @LLMDescription(WorkspaceToolDescriptions.WRITE_PATH)
        path: String,
        @LLMDescription(WorkspaceToolDescriptions.WRITE_CONTENT)
        content: String
    ): String = suspendToolGuard {
        val target = path.requireToolText("Path", MAX_PATH_CHARS)
        require(content.length <= MAX_CONTENT_CHARS) { "File content must be at most $MAX_CONTENT_CHARS characters" }

        placeAttachment()
        client.writeFile(id, target, content.toByteArray(Charsets.UTF_8))
        "Wrote `$target` (${content.length} chars) into the workspace. It is not in the chat until you send it."
    }

    @Tool
    @LLMDescription(WorkspaceToolDescriptions.SEND_FILES)
    suspend fun sendFromWorkspace(
        @LLMDescription(WorkspaceToolDescriptions.SEND_PATHS)
        paths: List<String>
    ): String = suspendToolGuard {
        require(paths.isNotEmpty()) { "At least one path is required" }

        val wanted = paths.map { it.requireToolText("Path", MAX_PATH_CHARS) }.distinct().take(MAX_SEND_FILES)
        val photos = mutableListOf<BotOutput.Photo>()
        val others = mutableListOf<BotOutput>()
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()

        wanted.forEach { path ->
            val bytes =
                runCatching { client.readFile(id, path) }
                    .getOrElse {
                        it.rethrowIfCancellation()
                        failed += "`$path` (${it.message ?: "unreadable"})"
                        return@forEach
                    }

            if (bytes.isEmpty()) {
                failed += "`$path` (empty)"
                return@forEach
            }

            if (bytes.size > MAX_UPLOAD_BYTES) {
                failed += "`$path` (${formatBytes(bytes.size.toLong())}, over the 50 MB upload limit)"
                return@forEach
            }

            val name = path.substringAfterLast('/').sanitizeFilename().ifBlank { "file" }
            when (name.substringAfterLast('.', "").lowercase()) {
                in IMAGE_EXTENSIONS -> photos += BotOutput.Photo(bytes = bytes, filename = name)
                in VIDEO_EXTENSIONS -> others += BotOutput.Video(bytes = bytes, filename = name)
                else -> others += BotOutput.Document(bytes = bytes, filename = name)
            }

            sent += name
        }

        when {
            photos.size == 1 -> outbox.enqueue(photos.single())
            photos.size >= 2 -> photos.chunked(MAX_MEDIA_GROUP).forEach { outbox.enqueue(BotOutput.PhotoGroup(it)) }
        }

        others.forEach { outbox.enqueue(it) }

        buildString {
            if (sent.isNotEmpty()) {
                append("Sending ${sent.size} file(s) to the chat: ${sent.joinToString(", ")}. ")
                append("Say what they are; do not paste their contents.")
            }

            if (failed.isNotEmpty()) {
                if (sent.isNotEmpty()) appendLine()
                append("Not sent: ${failed.joinToString(", ")}.")
            }
        }.ifBlank { "Nothing was sent." }
    }

    /**
     * Copies the message's attachment into the workspace the first time any tool runs, so a command
     * can read it without a tool of its own. Failure is reported to the model rather than thrown:
     * the command it was about to run is usually still worth running.
     */
    private suspend fun placeAttachment(): String? {
        if (attachmentHandled) return null.also { attachmentNote = null }
        attachmentHandled = true

        val file = attachedFile ?: return null
        val name = file.name.sanitizeFilename().ifBlank { "attachment" }

        if (file.fileSizeBytes != null && file.fileSizeBytes > MAX_ATTACHMENT_BYTES) {
            return "The attached file `$name` is too large to place in the workspace (limit 20 MB)."
        }

        val bytes =
            runCatching { file.loadBytes() }
                .getOrElse {
                    it.rethrowIfCancellation()
                    return "The attached file `$name` could not be downloaded into the workspace."
                }

        if (bytes.size > MAX_ATTACHMENT_BYTES) {
            return "The attached file `$name` is too large to place in the workspace (limit 20 MB)."
        }

        return runCatching {
            client.writeFile(id, "inbox/$name", bytes)
            "The attached file is in the workspace at `inbox/$name`."
        }.getOrElse {
            it.rethrowIfCancellation()
            "The attached file `$name` could not be written into the workspace."
        }
    }

    private fun quotaWarning(result: ExecResponse): String? {
        if (result.quotaBytes <= 0 || result.usedBytes <= 0) return null
        if (result.usedBytes * 10 < result.quotaBytes * 9) return null

        return "The workspace is nearly full (${formatBytes(result.usedBytes)} of " +
                "${formatBytes(result.quotaBytes)}). Delete what is no longer needed."
    }
}

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
    else "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
