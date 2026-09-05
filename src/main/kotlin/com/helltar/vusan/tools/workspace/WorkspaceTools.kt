package com.helltar.vusan.tools.workspace

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
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
import java.util.UUID

private const val MAX_COMMAND_CHARS = 16_000
private const val MAX_CONTENT_CHARS = 400_000
private const val MAX_PATH_CHARS = 400
private const val MAX_SEND_FILES = 10
private const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm")

@Suppress("unused")
class WorkspaceTools(
    private val client: WorkspaceClient,
    context: RequestContext,
    private val outbox: BotOutbox,
    private val attachedFile: AttachedFile? = null
) : ToolSet {
    private val id = workspaceId(context)
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
        require(timeoutSeconds >= 0) { "Timeout must not be negative" }
        val note = placeAttachment()
        val result = client.exec(id, script, timeoutSeconds.takeIf { it > 0 })
        listOfNotNull(note, describeCommand(result)).joinToString("\n")
    }

    @Tool
    @LLMDescription(WorkspaceToolDescriptions.READ_COMMAND)
    suspend fun readWorkspaceCommand(
        @LLMDescription(WorkspaceToolDescriptions.READ_JOB_ID)
        jobId: String = "",
        @LLMDescription(WorkspaceToolDescriptions.OFFSET)
        offset: Long = 0,
        @LLMDescription(WorkspaceToolDescriptions.WAIT_SECONDS)
        waitSeconds: Int = 10
    ): String = suspendToolGuard {
        require(offset >= 0) { "Offset must not be negative" }
        require(waitSeconds in 0..20) { "Wait must be between 0 and 20 seconds" }
        if (jobId.isBlank()) {
            client.listCommands(id).joinToString("\n") { "${it.jobId}: ${it.status.name.lowercase()}" }
                .ifBlank { "No recent commands in this workspace." }
        } else {
            describeCommand(client.readCommand(id, checkedJobId(jobId), offset, waitSeconds))
        }
    }

    @Tool
    @LLMDescription(WorkspaceToolDescriptions.CANCEL_COMMAND)
    suspend fun cancelWorkspaceCommand(
        @LLMDescription(WorkspaceToolDescriptions.JOB_ID)
        jobId: String
    ): String = suspendToolGuard {
        describeCommand(client.cancelCommand(id, checkedJobId(jobId)))
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
        val note = placeAttachment()
        client.writeFile(id, target, content.toByteArray(Charsets.UTF_8))
        listOfNotNull(note, "Wrote `$target` (${content.length} chars). Use sendFromWorkspace to deliver it.").joinToString("\n")
    }

    @Tool
    @LLMDescription(WorkspaceToolDescriptions.SEND_FILES)
    suspend fun sendFromWorkspace(
        @LLMDescription(WorkspaceToolDescriptions.SEND_PATHS)
        paths: List<String>
    ): String = suspendToolGuard {
        require(paths.isNotEmpty()) { "At least one path is required" }
        require(paths.size <= MAX_SEND_FILES) { "At most $MAX_SEND_FILES files per call" }
        val wanted = paths.map { it.requireToolText("Path", MAX_PATH_CHARS) }.distinct()
        val photos = mutableListOf<BotOutput.Photo>()
        val others = mutableListOf<BotOutput>()
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var remainingBytes = WORKSPACE_FILE_LIMIT

        wanted.forEach { path ->
            val bytes = runCatching {
                require(remainingBytes > 0) { "The 50 MB transfer budget has been used" }
                client.readFile(id, path, remainingBytes)
            }.getOrElse {
                it.rethrowIfCancellation()
                failed += "`$path` (${it.message ?: "unreadable"})"
                return@forEach
            }
            if (bytes.isEmpty()) {
                failed += "`$path` (empty)"
                return@forEach
            }
            remainingBytes -= bytes.size
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
            photos.size > 1 -> outbox.enqueue(BotOutput.PhotoGroup(photos))
        }
        others.forEach { outbox.enqueue(it) }
        buildString {
            if (sent.isNotEmpty()) appendLine("Sending ${sent.size} file(s): ${sent.joinToString(", ")}. Say what they are; do not paste their contents.")
            if (failed.isNotEmpty()) appendLine("Not sent: ${failed.joinToString(", ")}.")
        }.trim()
    }

    private suspend fun placeAttachment(): String? {
        if (attachmentHandled) return null
        attachmentHandled = true
        val file = attachedFile ?: return null
        val name = file.name.sanitizeFilename().ifBlank { "attachment" }
        if ((file.fileSizeBytes ?: 0) > MAX_ATTACHMENT_BYTES) return "The attached file `$name` exceeds the 20 MB input limit."
        return runCatching {
            val bytes = file.loadBytes()
            require(bytes.size <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds the 20 MB input limit" }
            val path = "inbox/${UUID.randomUUID()}/$name"
            client.writeFile(id, path, bytes)
            "The attached file is in the workspace at `$path`."
        }.getOrElse {
            it.rethrowIfCancellation()
            "The attached file `$name` could not be placed in the workspace: ${it.message}."
        }
    }
}

private fun checkedJobId(value: String): String {
    val id = value.requireToolText("Job ID", 36)
    require(UUID.fromString(id).toString() == id) { "Invalid job ID" }
    return id
}

private fun describeCommand(result: CommandResult): String = buildString {
    appendLine("Job ${result.jobId}: ${result.status.name.lowercase()}.")
    result.output.takeIf { it.isNotBlank() }?.let { appendLine(xmlBlock("command_output", it)) }
    result.exitCode?.let { appendLine("Exit code $it.") }
    result.error?.let { appendLine(it) }
    when (result.status) {
        CommandStatus.RUNNING -> appendLine("The command is still running. Read it again with readWorkspaceCommand.")
        CommandStatus.TIMED_OUT, CommandStatus.CANCELLED -> appendLine("All processes in this workspace were stopped; files were kept.")
        CommandStatus.INTERRUPTED -> appendLine("The service restarted; files were kept. Check the project before retrying.")
        else -> Unit
    }
    if (result.hasMore || result.status == CommandStatus.RUNNING) appendLine("Continue reading with offset=${result.nextOffset}.")
    if (result.truncated) appendLine("The command reached the stored log limit; later output was discarded.")
    if (result.diskWarning) appendLine("Workspace disk use is above the warning threshold. Clean up files that are no longer needed.")
    if (result.elapsedMs >= 1000) appendLine("Elapsed: ${"%.1f".format(Locale.ROOT, result.elapsedMs / 1000.0)}s.")
}.trim()
