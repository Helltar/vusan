package com.helltar.vusan.tools.sandbox

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.util.Locale
import java.util.UUID

private const val MAX_COMMAND_CHARS = 16_000
private const val MAX_CONTENT_CHARS = 400_000
private const val MAX_PATH_CHARS = 400
private const val MAX_SEND_FILES = 10
private const val MAX_JOB_ID_CHARS = 64
private const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "webm")

@Suppress("unused")
class SandboxTools(
    // shared with every other tool of the turn, so a reset here is a reset for them too
    private val sandbox: SandboxClient.PersonSandbox,
    private val outbox: BotOutbox,
    private val attachedFile: AttachedFile? = null,
) : ToolSet {

    private var attachmentHandled = false

    @Tool
    @LLMDescription(SandboxToolDescriptions.RUN_COMMAND)
    suspend fun runCommand(
        @LLMDescription(SandboxToolDescriptions.COMMAND)
        command: String,
        @LLMDescription(SandboxToolDescriptions.TIMEOUT_SECONDS)
        timeoutSeconds: Int = 0,
    ): String = suspendToolGuard {
        val script = command.requireToolText("Command", MAX_COMMAND_CHARS)
        require(timeoutSeconds >= 0) { "Timeout must not be negative" }
        val note = placeAttachment()
        val result = sandbox.exec(script, timeoutSeconds.takeIf { it > 0 })
        listOfNotNull(note, describeCommand(result)).joinToString("\n")
    }

    @Tool
    @LLMDescription(SandboxToolDescriptions.READ_COMMAND)
    suspend fun readSandboxCommand(
        @LLMDescription(SandboxToolDescriptions.READ_JOB_ID)
        jobId: String = "",
        @LLMDescription(SandboxToolDescriptions.OFFSET)
        offset: Long = 0,
        @LLMDescription(SandboxToolDescriptions.WAIT_SECONDS)
        waitSeconds: Int = 10,
    ): String = suspendToolGuard {
        require(offset >= 0) { "Offset must not be negative" }
        require(waitSeconds in 0..20) { "Wait must be between 0 and 20 seconds" }

        if (jobId.isBlank()) {
            sandbox.listCommands().joinToString("\n") { "${it.jobId}: ${it.status.name.lowercase()}" }
                .ifBlank { "No recent commands in this sandbox." }
        } else {
            describeCommand(sandbox.readCommand(checkedJobId(jobId), offset, waitSeconds))
        }
    }

    @Tool
    @LLMDescription(SandboxToolDescriptions.CANCEL_COMMAND)
    suspend fun cancelSandboxCommand(
        @LLMDescription(SandboxToolDescriptions.JOB_ID)
        jobId: String,
    ): String = suspendToolGuard {
        describeCommand(sandbox.cancelCommand(checkedJobId(jobId)))
    }

    @Tool
    @LLMDescription(SandboxToolDescriptions.WRITE_FILE)
    suspend fun writeSandboxFile(
        @LLMDescription(SandboxToolDescriptions.WRITE_PATH)
        path: String,
        @LLMDescription(SandboxToolDescriptions.WRITE_CONTENT)
        content: String,
    ): String = suspendToolGuard {
        val target = path.requireToolText("Path", MAX_PATH_CHARS)
        require(content.length <= MAX_CONTENT_CHARS) { "File content must be at most $MAX_CONTENT_CHARS characters" }
        val note = placeAttachment()
        sandbox.writeFile(target, content.toByteArray(Charsets.UTF_8))
        listOfNotNull(note, "Wrote `$target` (${content.length} chars). Use sendFromSandbox to deliver it.").joinToString("\n")
    }

    @Tool
    @LLMDescription(SandboxToolDescriptions.DELETE_FILE)
    suspend fun deleteSandboxFile(
        @LLMDescription(SandboxToolDescriptions.DELETE_PATH)
        path: String,
    ): String = suspendToolGuard {
        val target = path.requireToolText("Path", MAX_PATH_CHARS)
        sandbox.deleteFile(target)
        "Deleted `$target`. Everything else was kept, and running commands were left alone."
    }

    @Tool
    @LLMDescription(SandboxToolDescriptions.RESET_SANDBOX)
    suspend fun resetSandbox(): String = suspendToolGuard {
        sandbox.reset()
        "The sandbox is empty again. Every file and installed dependency is gone, and so is any site published from it; the next command starts in a new home."
    }

    @Tool
    @LLMDescription(SandboxToolDescriptions.SEND_FILES)
    suspend fun sendFromSandbox(
        @LLMDescription(SandboxToolDescriptions.SEND_PATHS)
        paths: List<String>,
    ): String = suspendToolGuard {
        require(paths.isNotEmpty()) { "At least one path is required" }
        require(paths.size <= MAX_SEND_FILES) { "At most $MAX_SEND_FILES files per call" }
        val wanted = paths.map { it.requireToolText("Path", MAX_PATH_CHARS) }.distinct()
        val photos = mutableListOf<BotOutput.Photo>()
        val others = mutableListOf<Pair<BotOutput, String>>()
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var remainingBytes = SANDBOX_FILE_LIMIT

        wanted.forEach { path ->
            val bytes = runCatching {
                require(remainingBytes > 0) { "The 50 MB transfer budget has been used" }
                sandbox.readFile(path, remainingBytes)
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
                in VIDEO_EXTENSIONS -> others += BotOutput.Video(bytes = bytes, filename = name) to name
                else -> others += BotOutput.Document(bytes = bytes, filename = name) to name
            }
            sent += name
        }
        // a chat can forbid a whole kind of content, and the file the model picked may be one of them.
        // the queue refuses those, and saying so is the point: reporting a photo as sent into a chat
        // that drops it leaves the model believing the user can see something nobody sent.
        val refused = mutableListOf<String>()

        when {
            photos.size == 1 -> if (!outbox.enqueue(photos.single())) refused += photos.single().filename
            photos.size > 1 -> if (!outbox.enqueue(BotOutput.PhotoGroup(photos))) refused += photos.map { it.filename }
        }

        others.forEach { (output, name) ->
            if (!outbox.enqueue(output)) refused += name
        }

        val queued = sent - refused.toSet()

        buildString {
            if (queued.isNotEmpty()) appendLine("Sending ${queued.size} file(s): ${queued.joinToString(", ")}. Say what they are; do not paste their contents.")
            if (refused.isNotEmpty()) appendLine("This chat does not accept these, so they were not sent: ${refused.joinToString(", ")}.")
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
            sandbox.writeFile(path, bytes)
            "The attached file is in the sandbox at `$path`."
        }.getOrElse {
            it.rethrowIfCancellation()
            "The attached file `$name` could not be placed in the sandbox: ${it.message}."
        }
    }
}

// the server's id shape is the sdk's to check; this only keeps model text short before it is echoed back.
private fun checkedJobId(value: String): String = value.requireToolText("Job ID", MAX_JOB_ID_CHARS)

private fun describeCommand(result: CommandResult): String = buildString {
    appendLine("Job ${result.jobId}: ${result.status.name.lowercase()}.")
    result.output.takeIf { it.isNotBlank() }?.let { appendLine(xmlBlock("command_output", it)) }
    result.exitCode?.let { appendLine("Exit code $it.") }

    when (result.limit) {
        CommandLimit.OUT_OF_MEMORY ->
            appendLine("The sandbox ran out of memory and a process was killed. Work on less at once — smaller inputs, one step at a time.")
        CommandLimit.TOO_MANY_PROCESSES ->
            appendLine("The sandbox reached its process limit. Run fewer things at once, for example `make -j2` or fewer workers.")
        null -> Unit
    }

    when (result.status) {
        CommandStatus.RUNNING -> appendLine("The command is still running. Read it again with readSandboxCommand.")
        CommandStatus.TIMED_OUT -> appendLine("It ran past its time limit and was stopped; files were kept.")
        CommandStatus.CANCELLED -> appendLine("It was cancelled with every process it started; files were kept.")
        CommandStatus.INTERRUPTED ->
            appendLine("The sandbox stopped under it (${result.reason ?: "reason unknown"}); files were kept. Check the project before retrying.")
        else -> Unit
    }

    if (result.hasMore) appendLine("Continue reading with offset=${result.nextOffset}.")
    if (result.truncated) appendLine("The command reached the stored log limit; part of the output was dropped.")
    if (result.elapsedMs >= 1000) appendLine("Elapsed: ${"%.1f".format(Locale.ROOT, result.elapsedMs / 1000.0)}s.")
}.trim()
