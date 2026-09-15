package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.HttpClient
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecRequest
import io.reified.regolith.protocol.ExecStatus
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.PublishedSite as RegolithSite
import io.reified.regolith.protocol.Reasons
import io.reified.regolith.sdk.OutputSoFar
import io.reified.regolith.sdk.RegolithClient
import io.reified.regolith.sdk.RegolithConnectionException
import io.reified.regolith.sdk.RegolithException
import io.reified.regolith.sdk.Sandbox
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

internal const val SANDBOX_FILE_LIMIT = 50 * 1024 * 1024

/**
 * The bot's side of a Regolith server: one sandbox per person, holding their home.
 *
 * This is the only file that uses Regolith's Kotlin SDK. It turns the SDK's calls into what the tools
 * describe to the model — a command's output so far and where to read on — and its refusals into text
 * the model can act on.
 *
 * A person is an alias on that server. [sandboxOf] hands back a [PersonSandbox] for one turn, which
 * asks the server for the sandbox behind the alias on first use and keeps it for the rest of the turn;
 * nothing here remembers it between turns, so a home deleted by retention is simply made again.
 */
class SandboxClient(
    http: HttpClient,
    baseUrl: String,
    token: String,
) {

    // the sdk never follows a redirect, so the bearer secret cannot be relayed to another origin.
    private val regolith = RegolithClient(baseUrl, token, http)

    /**
     * The person's sandbox for one turn's work. Nothing is sent until a tool uses it, and every tool of
     * the turn should share this one, so the site is published from the sandbox the commands ran in.
     */
    fun sandboxOf(person: String): PersonSandbox = PersonSandbox(person)

    /** One person's sandbox, for as long as the work that asked for it lasts. */
    inner class PersonSandbox internal constructor(private val person: String) {

        // created or found by the alias on first use; a reset forgets it, so the next use makes a new one.
        @Volatile
        private var opened: Sandbox? = null

        suspend fun exec(command: String, timeoutSeconds: Int?): CommandResult {
            val request = ExecRequest(shell = command, timeoutSeconds = clamped(timeoutSeconds))
            val exec = call { sandbox().startExec(request) }

            return read(exec.id, offset = 0, waitSeconds = FIRST_WAIT_SECONDS)
        }

        suspend fun readCommand(jobId: String, offset: Long, waitSeconds: Int): CommandResult = read(jobId, offset, waitSeconds)

        suspend fun cancelCommand(jobId: String): CommandResult =
            call { sandbox().exec(jobId).cancel() }.result(output = "", nextOffset = 0, hasMore = false)

        suspend fun listCommands(): List<CommandResult> =
            call { sandbox().execs() }.map { it.result(output = "", nextOffset = 0, hasMore = false) }

        suspend fun writeFile(path: String, bytes: ByteArray) {
            require(bytes.size <= SANDBOX_FILE_LIMIT) { "File exceeds the 50 MB transfer limit" }
            call { sandbox().files.write(path, bytes) }
        }

        suspend fun readFile(path: String, maxBytes: Int = SANDBOX_FILE_LIMIT): ByteArray {
            require(maxBytes in 1..SANDBOX_FILE_LIMIT) { "Invalid file transfer budget" }

            return call {
                try {
                    sandbox().files.read(path, maxBytes.toLong())
                } catch (e: RegolithException) {
                    // only here, where the bound is this call's budget, does the code mean the file does not fit
                    // in it: elsewhere it is a site too large, which the server's own words explain.
                    if (e.code == ErrorCodes.PAYLOAD_TOO_LARGE) error("File exceeds the remaining transfer limit") else throw e
                }
            }
        }

        suspend fun deleteFile(path: String) {
            call { sandbox().files.delete(path, recursive = true) }
        }

        /** Deletes the sandbox with its home; the next use creates an empty one for this person. */
        suspend fun reset() {
            call { sandbox().delete() }
            opened = null
        }

        /** Publishes a directory of the sandbox to the web and returns the address it is served at. */
        suspend fun publishSite(path: String): PublishedSite = call { sandbox().publish(path) }.published()

        /** What is published for this person, or null when nothing is. */
        suspend fun publishedSite(): PublishedSite? = call { sandbox().siteOrNull() }?.published()

        /** Takes the site down; false when there was nothing to take down. The sandbox keeps its files. */
        suspend fun unpublishSite(): Boolean = call { sandbox().unpublish() }

        private suspend fun sandbox(): Sandbox = opened ?: call { regolith.getOrCreate(person) }.also { opened = it }

        /**
         * Reads recorded output from [offset] until the command ends, this call's budget runs out or the
         * output limit is reached, and reports the command as it now stands. Output is kept on the server,
         * so what does not fit is read by the next call from [CommandResult.nextOffset].
         */
        private suspend fun read(jobId: String, offset: Long, waitSeconds: Int): CommandResult {
            val exec = sandbox().exec(jobId)
            val read = call { exec.readWithin(offset, waitSeconds.seconds, OUTPUT_CHARS) }

            return read.exec.result(read)
        }
    }

    /** The server owns its limits; a timeout above the ceiling would be refused instead of trimmed. */
    private suspend fun clamped(timeoutSeconds: Int?): Int? {
        if (timeoutSeconds == null) return null
        val ceiling = limitOrNull()?.takeIf { it > 0 } ?: return timeoutSeconds

        return minOf(timeoutSeconds, ceiling)
    }

    /** The server's exec timeout ceiling; the sdk keeps what the server said, so this asks once. */
    private suspend fun limitOrNull(): Int? =
        runCatching { call { regolith.info() }.limits.maxExecTimeoutSeconds }
            .onFailure { it.rethrowIfCancellation() }
            .getOrNull()

    /** Runs one SDK call, turning a refusal or an unanswering server into text the model can act on. */
    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (e: RegolithException) {
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

private fun RegolithException.explain(): String = when (code) {
    ErrorCodes.CAPACITY_EXHAUSTED, ErrorCodes.UNAVAILABLE -> {
        val wait = retryAfter?.let { "in about ${it.inWholeSeconds.coerceAtLeast(1)} seconds" } ?: "shortly"
        "The sandbox host is at capacity right now. Tell the user and try again $wait."
    }
    ErrorCodes.BUSY -> "This sandbox already runs as many commands as it may; wait for one to finish."
    // anything but a problem document came from something in front of the server, such as a proxy.
    else -> if (code.startsWith("http_")) {
        "The sandbox API answered $status"
    } else {
        detail.ifBlank { "The sandbox refused the request" }
    }
}

private fun RegolithSite.published(): PublishedSite = PublishedSite(url, files, bytes, publishedAt, hasIndex)

private fun ExecInfo.result(read: OutputSoFar): CommandResult =
    result(read.text, read.nextOffset, hasMore = !read.complete, dropped = read.gapped)

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
