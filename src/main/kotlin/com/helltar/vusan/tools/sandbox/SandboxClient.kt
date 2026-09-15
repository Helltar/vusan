package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.HttpClient
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecRequest
import io.reified.regolith.protocol.ExecStatus
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.OutputKind
import io.reified.regolith.protocol.PublishedSite as RegolithSite
import io.reified.regolith.protocol.Reasons
import io.reified.regolith.protocol.ServerInfo
import io.reified.regolith.sdk.RegolithClient
import io.reified.regolith.sdk.RegolithConnectionException
import io.reified.regolith.sdk.RegolithException
import io.reified.regolith.sdk.Sandbox
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal const val SANDBOX_FILE_LIMIT = 50 * 1024 * 1024

/**
 * The bot's side of a Regolith server: one named sandbox per person, holding their home.
 *
 * This is the only file that uses Regolith's Kotlin SDK. It turns the SDK's calls into what the tools
 * describe to the model — a command's output so far and where to read on — and its refusals into text
 * the model can act on. A sandbox is created on first use and returned unchanged after that, so no
 * state is kept here beyond remembering which ones this process has already asked for.
 */
class SandboxClient(
    http: HttpClient,
    baseUrl: String,
    token: String,
) {

    // the sdk never follows a redirect, so the bearer secret cannot be relayed to another origin.
    private val regolith = RegolithClient(baseUrl, token, http)

    // sandboxes this process has created or found; a request that misses one clears it again.
    private val created = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var server: ServerInfo? = null

    suspend fun exec(sandboxId: String, command: String, timeoutSeconds: Int?): CommandResult {
        val sandbox = created(sandboxId)
        val request = ExecRequest(shell = command, timeoutSeconds = clamped(timeoutSeconds))
        val exec = call(sandboxId) { sandbox.startExec(request) }

        return collect(sandboxId, exec.id, offset = 0, waitSeconds = FIRST_WAIT_SECONDS)
    }

    suspend fun readCommand(sandboxId: String, jobId: String, offset: Long, waitSeconds: Int): CommandResult =
        collect(sandboxId, jobId, offset, waitSeconds)

    suspend fun cancelCommand(sandboxId: String, jobId: String): CommandResult =
        call(sandboxId) { regolith.sandbox(sandboxId).exec(jobId).cancel() }
            .result(output = "", nextOffset = 0, hasMore = false)

    suspend fun listCommands(sandboxId: String): List<CommandResult> =
        call(sandboxId) { regolith.sandbox(sandboxId).execs() }
            .map { it.result(output = "", nextOffset = 0, hasMore = false) }

    suspend fun writeFile(sandboxId: String, path: String, bytes: ByteArray) {
        require(bytes.size <= SANDBOX_FILE_LIMIT) { "File exceeds the 50 MB transfer limit" }
        val sandbox = created(sandboxId)
        call(sandboxId) { sandbox.files.write(path, bytes) }
    }

    suspend fun deleteFile(sandboxId: String, path: String) {
        call(sandboxId) { regolith.sandbox(sandboxId).files.delete(path, recursive = true) }
    }

    /** Publishes a directory of the sandbox to the web and returns the address it is served at. */
    suspend fun publishSite(sandboxId: String, path: String): PublishedSite {
        val sandbox = created(sandboxId)

        return call(sandboxId) { sandbox.publish(path) }.published()
    }

    /** What is published for this person, or null when nothing is. */
    suspend fun publishedSite(sandboxId: String): PublishedSite? = call(sandboxId) {
        whenPresent(absent = null) { regolith.sandbox(sandboxId).site().published() }
    }

    /** Takes the site down; false when there was nothing to take down. The sandbox keeps its files. */
    suspend fun unpublishSite(sandboxId: String): Boolean = call(sandboxId) {
        whenPresent(absent = false) {
            regolith.sandbox(sandboxId).unpublish()
            true
        }
    }

    /** The names in one directory of the sandbox, for a check before something is published. */
    suspend fun entries(sandboxId: String, path: String): List<String> =
        call(sandboxId) { regolith.sandbox(sandboxId).files.list(path) }.map { it.name }

    /** Deletes the sandbox with its home; the next use creates an empty one under the same name. */
    suspend fun resetSandbox(sandboxId: String) {
        call(sandboxId) { regolith.sandbox(sandboxId).delete() }
        created -= sandboxId
    }

    suspend fun readFile(sandboxId: String, path: String, maxBytes: Int = SANDBOX_FILE_LIMIT): ByteArray {
        require(maxBytes in 1..SANDBOX_FILE_LIMIT) { "Invalid file transfer budget" }

        return call(sandboxId) {
            try {
                regolith.sandbox(sandboxId).files.read(path, maxBytes.toLong())
            } catch (e: RegolithException) {
                // only here, where the bound is this call's budget, does the code mean the file does not fit in it:
                // elsewhere it can be a full home or a site too large, which the server's own words explain.
                if (e.code == ErrorCodes.PAYLOAD_TOO_LARGE) error("File exceeds the remaining transfer limit") else throw e
            }
        }
    }

    /**
     * Reads recorded output from [offset] until the command ends, this call's budget runs out, or the
     * output limit is reached, then reports the command as it now stands. Output is kept on the server,
     * so what does not fit is read by the next call from [CommandResult.nextOffset].
     */
    private suspend fun collect(sandboxId: String, jobId: String, offset: Long, waitSeconds: Int): CommandResult {
        val exec = regolith.sandbox(sandboxId).exec(jobId)
        val started = TimeSource.Monotonic.markNow()
        val output = StringBuilder()
        var next = offset
        var complete: Boolean
        var dropped = false

        while (true) {
            val remaining = (waitSeconds.seconds - started.elapsedNow()).inWholeSeconds.toInt().coerceAtLeast(0)
            val page = call(sandboxId) { exec.readOutput(next, remaining, OUTPUT_CHARS) }
            page.frames.forEach { frame ->
                if (frame.kind == OutputKind.GAP) dropped = true else output.append(frame.text)
            }
            next = page.nextOffset
            complete = page.complete
            // the server holds the request until there is output or the wait runs out, so an empty page
            // means nothing more arrived in time: looping again would only spin against it.
            val arrived = page.frames.isNotEmpty()
            val room = remaining > 0 && output.length < OUTPUT_CHARS
            if (complete || !arrived || !room) break
        }

        val info = call(sandboxId) { exec.info() }

        return info.result(output.toString(), next, hasMore = !complete, dropped = dropped)
    }

    /** The person's sandbox, created on first use and left exactly as it is after that. */
    private suspend fun created(sandboxId: String): Sandbox {
        val sandbox = regolith.sandbox(sandboxId)
        if (!created.add(sandboxId)) return sandbox

        try {
            call(sandboxId) { sandbox.getOrCreate() }
        } catch (e: Throwable) {
            created -= sandboxId
            throw e
        }

        return sandbox
    }

    /** The server owns its limits; a timeout above the ceiling would be refused instead of trimmed. */
    private suspend fun clamped(timeoutSeconds: Int?): Int? {
        if (timeoutSeconds == null) return null
        val ceiling = info()?.limits?.maxExecTimeoutSeconds ?: 0

        return if (ceiling > 0) minOf(timeoutSeconds, ceiling) else timeoutSeconds
    }

    /** What the server says about itself, read once, for the limits the client trims to. */
    private suspend fun info(): ServerInfo? {
        server?.let { return it }

        return runCatching { call(null) { regolith.info() } }
            .onFailure { it.rethrowIfCancellation() }
            .getOrNull()
            ?.also { server = it }
    }

    /** Runs one SDK call, turning a refusal or an unanswering server into text the model can act on. */
    private suspend fun <T> call(sandboxId: String?, block: suspend () -> T): T =
        try {
            block()
        } catch (e: RegolithException) {
            // the sandbox may have been deleted by retention or by hand: forget it, so the next call recreates it.
            if (e.code == ErrorCodes.NOT_FOUND && sandboxId != null) created -= sandboxId
            error(e.explain())
        } catch (_: RegolithConnectionException) {
            error(UNAVAILABLE)
        }

    private companion object {
        const val OUTPUT_CHARS = 16 * 1024
        const val FIRST_WAIT_SECONDS = 10
        const val UNAVAILABLE = "The sandbox is temporarily unavailable. Tell the user; do not retry immediately."
    }
}

/** A `not_found` here means the thing asked about is absent, not that the sandbox is gone. */
private inline fun <T> whenPresent(absent: T, block: () -> T): T =
    try {
        block()
    } catch (e: RegolithException) {
        if (e.code == ErrorCodes.NOT_FOUND) absent else throw e
    }

private fun RegolithException.explain(): String = when (code) {
    ErrorCodes.CAPACITY_EXHAUSTED, ErrorCodes.UNAVAILABLE ->
        "The sandbox host is at capacity right now. Tell the user and try again in a minute."
    ErrorCodes.BUSY -> "This sandbox already runs as many commands as it may; wait for one to finish."
    // anything but a problem document came from something in front of the server, such as a proxy.
    else -> if (code.startsWith("http_")) {
        "The sandbox API answered $status"
    } else {
        detail.ifBlank { "The sandbox refused the request" }
    }
}

private fun RegolithSite.published(): PublishedSite = PublishedSite(url, files, bytes, publishedAt)

private fun ExecInfo.result(output: String, nextOffset: Long, hasMore: Boolean, dropped: Boolean = false): CommandResult {
    val reason = outcome?.reason
    val status = commandStatus()

    return CommandResult(
        jobId = id,
        status = status,
        exitCode = outcome?.exitCode,
        output = output,
        nextOffset = nextOffset,
        hasMore = hasMore,
        truncated = dropped || outputTruncated,
        elapsedMs = ((finishedAt ?: Clock.System.now()) - startedAt).inWholeMilliseconds.coerceAtLeast(0),
        limit = when (reason) {
            Reasons.OOM_KILLED -> CommandLimit.OUT_OF_MEMORY
            Reasons.PIDS_LIMITED -> CommandLimit.TOO_MANY_PROCESSES
            else -> null
        },
        reason = reason.takeIf { status == CommandStatus.INTERRUPTED },
    )
}

private fun ExecInfo.commandStatus(): CommandStatus = when {
    status == ExecStatus.RUNNING -> CommandStatus.RUNNING
    else -> when (outcome?.type) {
        OutcomeType.EXITED -> CommandStatus.COMPLETED
        OutcomeType.TIMED_OUT -> CommandStatus.TIMED_OUT
        OutcomeType.CANCELLED -> CommandStatus.CANCELLED
        OutcomeType.INTERRUPTED, null -> CommandStatus.INTERRUPTED
    }
}
