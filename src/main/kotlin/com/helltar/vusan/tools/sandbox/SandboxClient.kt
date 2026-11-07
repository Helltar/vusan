package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.common.asFileSize
import com.helltar.vusan.agent.ShelfSandbox
import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.HttpClient
import io.reified.regolith.protocol.EntryType
import io.reified.regolith.protocol.ErrorCodes
import io.reified.regolith.protocol.ExecInfo
import io.reified.regolith.protocol.ExecRequest
import io.reified.regolith.protocol.ExecStatus
import io.reified.regolith.protocol.OutcomeType
import io.reified.regolith.protocol.PROTOCOL_VERSION
import io.reified.regolith.protocol.PublishedSite as RegolithSite
import io.reified.regolith.protocol.Reasons
import io.reified.regolith.protocol.SandboxState
import io.reified.regolith.protocol.ServerLimits
import io.reified.regolith.protocol.SnapshotInfo
import io.reified.regolith.sdk.OutputSoFar
import io.reified.regolith.sdk.RegolithClient
import io.reified.regolith.sdk.RegolithConnectionException
import io.reified.regolith.sdk.RegolithException
import io.reified.regolith.sdk.Sandbox
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// the most of one file the bot moves to or from a sandbox: the sdk carries a file as one ByteArray, so it is
// held in memory whole. the server's own maxFileBytes may be lower, and then it is the limit
private const val BOT_FILE_LIMIT = 100 * 1024 * 1024

/**
 * The bot's side of a Regolith server: one sandbox per person, holding their home.
 *
 * The only user of Regolith's Kotlin SDK. It turns the SDK's calls into what the tools describe (output so
 * far and where to read on) and its refusals into text the model can act on.
 *
 * A person is an alias on the server. [sandboxOf] returns a [PersonSandbox] for one turn, which looks up the
 * sandbox on first use and keeps it for the turn; nothing is remembered between turns, so a home deleted by
 * retention is simply made again.
 */
class SandboxClient(
    http: HttpClient,
    baseUrl: String,
    token: String,
) {

    // the sdk never follows redirects, so the bearer secret cannot reach another origin.
    private val regolith = RegolithClient(baseUrl, token, http)

    /**
     * The person's sandbox for one turn. Nothing is sent until a tool uses it; every tool of the turn should
     * share it, so a site is published from the sandbox the commands ran in.
     */
    fun sandboxOf(person: String): PersonSandbox = PersonSandbox(person)

    /**
     * What the server runs, for the startup summary; null when it does not answer in time. The SDK keeps the
     * answer, so the first command's timeout ceiling needs no request of its own.
     */
    suspend fun serverOrNull(): SandboxServer? =
        withTimeoutOrNull(STARTUP_WAIT) {
            runCatching { regolith.info() }
                .onFailure { it.rethrowIfCancellation() }
                .getOrNull()
        }?.let { SandboxServer(it.version, it.protocol, PROTOCOL_VERSION, it.publishing) }

    /** One person's sandbox, for as long as the work that asked for it lasts. */
    inner class PersonSandbox internal constructor(private val person: String) : ShelfSandbox {

        // created or found by alias on first use; a reset forgets it.
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

        suspend fun listCommands(): List<CommandResult> {
            val sandbox = existing() ?: return emptyList()

            return call { sandbox.execs() }.map { it.result(output = "", nextOffset = 0, hasMore = false) }
        }

        override suspend fun fileLimitBytes(): Int = this@SandboxClient.fileLimitBytes()

        suspend fun writeFile(path: String, bytes: ByteArray) {
            val limit = fileLimitBytes()
            require(bytes.size <= limit) { "File exceeds the ${limit.toLong().asFileSize()} transfer limit" }
            call { sandbox().files.write(path, bytes) }
        }

        suspend fun readFile(path: String, maxBytes: Int? = null): ByteArray {
            val limit = fileLimitBytes()
            val budget = maxBytes ?: limit
            require(budget in 1..limit) { "Invalid file transfer budget" }

            return call {
                try {
                    sandbox().files.read(path, budget.toLong())
                } catch (e: RegolithException) {
                    // only here, bounded by this call's budget, does the code mean the file does not fit;
                    // elsewhere it is a site too large, which the server explains.
                    if (e.code == ErrorCodes.PAYLOAD_TOO_LARGE) error("`$path` is larger than the ${budget.toLong().asFileSize()} this read may take") else throw e
                }
            }
        }

        // the shelf names its own cap, so a file over it is the shelf's complaint, not the transfer budget's
        override suspend fun read(path: String, maxBytes: Int): ByteArray =
            call {
                try {
                    sandbox().files.read(path, maxBytes.toLong())
                } catch (e: RegolithException) {
                    if (e.code == ErrorCodes.PAYLOAD_TOO_LARGE) throw ShelfSandbox.FileTooLarge(path, maxBytes) else throw e
                }
            }

        override suspend fun write(path: String, bytes: ByteArray) = writeFile(path, bytes)

        override suspend fun directories(path: String): List<String> =
            call {
                try {
                    sandbox().files.list(path)
                } catch (e: RegolithException) {
                    if (e.code == ErrorCodes.NOT_FOUND) emptyList() else throw e
                }
            }
                .filter { it.type == EntryType.DIRECTORY }
                .map { it.name }

        override suspend fun deleteDirectory(path: String) {
            call { sandbox().files.delete(path, recursive = true) }
        }

        /**
         * Deletes the sandbox with its home and snapshots; the next use creates an empty one. False when the
         * person had none.
         */
        suspend fun reset(): Boolean {
            val sandbox = existing() ?: return false
            call { sandbox.delete() }
            opened = null

            return true
        }

        /** The sandbox as the server reports it, or null when the person has none. */
        suspend fun status(): SandboxStatus? {
            val sandbox = existing() ?: return null
            val info = call { sandbox.get() }
            val snapshots = call { sandbox.snapshots() }

            return SandboxStatus(
                running = info.state == SandboxState.RUNNING,
                cpus = info.resources.cpus,
                memoryMb = info.resources.memoryMb,
                homeMb = info.resources.homeMb,
                lastUsedAt = info.lastUsedAt,
                deleteAfter = info.deleteAfter,
                homeReleasedAt = info.homeReleasedAt,
                lastSessionEnd = info.lastSessionEnd?.reason?.let(::sessionEnd),
                snapshots = snapshots.map { it.snapshot() },
                maxSnapshots = limitsOrNull()?.maxSnapshots,
            )
        }

        /**
         * Copies the home into a snapshot kept with [note]. The server stops the session to copy it, so whatever
         * still runs ends interrupted; the caller decides whether that is acceptable.
         */
        suspend fun takeSnapshot(note: String): SandboxSnapshot = call { sandbox().snapshot(note) }.snapshot()

        /** Puts the home back as the snapshot [id] kept it; null when the person has no such snapshot. */
        suspend fun restoreSnapshot(id: String): SandboxSnapshot? {
            val sandbox = existing() ?: return null
            // looked up first, so an id the model made up never reaches a request path
            val snapshot = call { sandbox.snapshots() }.firstOrNull { it.id == id } ?: return null
            call { sandbox.restore(snapshot.id) }

            return snapshot.snapshot()
        }

        /** Deletes the snapshot [id], leaving the home alone; null when the person has no such snapshot. */
        suspend fun deleteSnapshot(id: String): SandboxSnapshot? {
            val sandbox = existing() ?: return null
            val snapshot = call { sandbox.snapshots() }.firstOrNull { it.id == id } ?: return null
            call { sandbox.deleteSnapshot(snapshot.id) }

            return snapshot.snapshot()
        }

        /** Ends the session and every process in it, keeping the files; false when nothing was running. */
        suspend fun restart(): Boolean {
            val sandbox = existing() ?: return false
            if (call { sandbox.get() }.state != SandboxState.RUNNING) return false
            call { sandbox.stop() }

            return true
        }

        /**
         * Publishes a sandbox directory and returns its address. No term is sent: a first publish gets the
         * server's default, a later one keeps the site's term.
         */
        suspend fun publishSite(path: String): PublishedSite = call { sandbox().publish(path) }.published()

        /** Whether retention took the home this person's site was built from since it was last used. */
        suspend fun homeReleased(): Boolean = existing()?.let { sandbox -> call { sandbox.get() }.homeReleasedAt != null } ?: false

        /** What is published for this person, or null. */
        suspend fun publishedSite(): PublishedSite? = existing()?.let { sandbox -> call { sandbox.siteOrNull() } }?.published()

        /**
         * Moves the site's term to [until], earlier or later; null when nothing is published. A term past what
         * the server allows is refused in its own words.
         */
        suspend fun setSiteUntil(until: Instant): PublishedSite? {
            val sandbox = existing() ?: return null

            return call {
                try {
                    sandbox.setSiteTerm(until)
                } catch (e: RegolithException) {
                    if (e.code == ErrorCodes.NOT_FOUND) null else throw e
                }
            }?.published()
        }

        /** Takes the site down; false when there was none. The sandbox keeps its files. */
        suspend fun unpublishSite(): Boolean = existing()?.let { sandbox -> call { sandbox.unpublish() } } ?: false

        private suspend fun sandbox(): Sandbox = opened ?: call { regolith.getOrCreate(person) }.also { opened = it }

        // a read that finds nothing must not make a sandbox for someone who never used one
        private suspend fun existing(): Sandbox? = opened ?: call { regolith.byAlias(person) }?.also { opened = it }

        /**
         * Reads output from [offset] until the command ends, this call's budget runs out or the output limit
         * is hit, and reports the command's state. Output stays on the server; the next call reads on from
         * [CommandResult.nextOffset].
         */
        private suspend fun read(jobId: String, offset: Long, waitSeconds: Int): CommandResult {
            val exec = sandbox().exec(jobId)
            val read = call { exec.readWithin(offset, waitSeconds.seconds, OUTPUT_CHARS) }

            return read.exec.result(read)
        }
    }

    /**
     * The largest file that moves either way: the server's `maxFileBytes`, held to what the bot keeps in memory
     * at once. A server that does not say lets the bot's own limit stand; it refuses a larger file itself.
     */
    suspend fun fileLimitBytes(): Int {
        val server = limitsOrNull()?.maxFileBytes?.takeIf { it > 0 } ?: return BOT_FILE_LIMIT

        return minOf(server, BOT_FILE_LIMIT.toLong()).toInt()
    }

    /** The server owns its limits; a timeout above the ceiling would be refused, not trimmed. */
    private suspend fun clamped(timeoutSeconds: Int?): Int? {
        if (timeoutSeconds == null) return null
        val ceiling = limitsOrNull()?.maxExecTimeoutSeconds?.takeIf { it > 0 } ?: return timeoutSeconds

        return minOf(timeoutSeconds, ceiling)
    }

    /** The server's limits; the sdk keeps what the server said, so this asks once. */
    private suspend fun limitsOrNull(): ServerLimits? =
        runCatching { call { regolith.info() }.limits }
            .onFailure { it.rethrowIfCancellation() }
            .getOrNull()

    /** Runs one SDK call, turning a refusal or a silent server into text the model can act on. */
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

        // startup reports the server rather than waiting on it: an unreachable one is a warning, not a stop
        val STARTUP_WAIT = 10.seconds
    }
}

private fun RegolithException.explain(): String = when (code) {
    ErrorCodes.CAPACITY_EXHAUSTED -> "The sandbox host is at capacity right now. Tell the user and try again ${retryHint()}."
    // not capacity: the server says why it cannot serve (a failing health check, say), which the model should
    // read instead of waiting.
    ErrorCodes.UNAVAILABLE -> {
        val reason = detail.ifBlank { "the server is not serving requests" }
        "The sandbox is unavailable: $reason. Tell the user and try again ${retryHint()}."
    }
    ErrorCodes.BUSY -> "This sandbox already runs as many commands as it may; wait for one to finish."
    // anything but a problem document came from something in front of the server, such as a proxy.
    else -> if (code.startsWith("http_")) {
        "The sandbox API answered $status"
    } else {
        detail.ifBlank { "The sandbox refused the request" }
    }
}

/** The wait the server asked for, never one made up here. */
private fun RegolithException.retryHint(): String =
    retryAfter?.let { "in about ${it.inWholeSeconds.coerceAtLeast(1)} seconds" } ?: "shortly"

/**
 * Why a session ended, as one sentence the model can act on. The protocol names every reason a server sends,
 * and a newer server may send one this build does not know, which is passed on as the server wrote it.
 */
private fun sessionEnd(reason: String): String = when (reason) {
    Reasons.IDLE -> "It stopped after sitting idle, which is normal; the next command starts it again."
    Reasons.STOPPED -> "It was stopped on purpose, by a snapshot, a restore or a restart."
    Reasons.SESSION_EXPIRED -> "It ran as long as a session may; the next command starts a new one."
    Reasons.CAPACITY -> "It was stopped while idle to make room for another sandbox."
    Reasons.CPU_LIMIT ->
        "Processes left running in the background burned too much CPU while no command ran; do not leave busy processes behind."
    Reasons.CONTAINER_EXITED ->
        "A command killed the sandbox's own keep-alive process (`pkill sleep`, `kill -9 -1`); stop processes by their PID or by what you started, never by name."
    Reasons.UNRESPONSIVE ->
        "Processes left in the background filled the process limit, so nothing more could start; wait for background jobs instead of leaving them running."
    Reasons.POLICY_FAILED -> "Its network rules were lost and had to be put back."
    Reasons.SANDBOX_DELETED -> "The sandbox was reset."
    Reasons.SERVER_RESTARTED -> "The sandbox host restarted."
    else -> "The sandbox host gave the reason `$reason`."
}

private fun RegolithSite.published(): PublishedSite = PublishedSite(url, files, bytes, publishedAt, until, hasIndex)

private fun SnapshotInfo.snapshot(): SandboxSnapshot = SandboxSnapshot(id, createdAt, bytes, note)

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
        interruption = reason?.takeIf { status == CommandStatus.INTERRUPTED }?.let(::sessionEnd),
    )
}

private fun ExecInfo.commandStatus(): CommandStatus = when {
    status == ExecStatus.RUNNING -> CommandStatus.RUNNING
    else -> when (outcome?.type) {
        OutcomeType.EXITED -> CommandStatus.COMPLETED
        OutcomeType.TIMED_OUT -> CommandStatus.TIMED_OUT
        OutcomeType.CANCELED -> CommandStatus.CANCELED
        OutcomeType.INTERRUPTED, null -> CommandStatus.INTERRUPTED
    }
}
