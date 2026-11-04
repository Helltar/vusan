package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.tools.SANDBOX_REFERENCE_PREFIX
import kotlin.time.Instant

/** How a command ended, as the tools describe it. */
enum class CommandStatus { RUNNING, COMPLETED, TIMED_OUT, CANCELED, INTERRUPTED }

/** A session limit explaining a failed command; the whole sandbox hit it, not one process. */
enum class CommandLimit { OUT_OF_MEMORY, TOO_MANY_PROCESSES }

/**
 * One command as the model sees it: its id to poll with, its output so far, and where the next read starts.
 */
data class CommandResult(
    val jobId: String,
    val status: CommandStatus,
    val exitCode: Int? = null,
    val output: String = "",
    val nextOffset: Long = 0,
    val hasMore: Boolean = false,
    val truncated: Boolean = false,
    val elapsedMs: Long = 0,
    val limit: CommandLimit? = null,
    /**
     * Why the session ended under the command, in words the model can act on; only for
     * [CommandStatus.INTERRUPTED].
     */
    val interruption: String? = null,
)

/**
 * A published site as the tools describe it: its address, contents, when it was taken, until when it stays
 * up, and whether a top-level `index.html`, the page the address opens, exists.
 */
data class PublishedSite(
    val url: String,
    val files: Int,
    val bytes: Long,
    val publishedAt: Instant,
    val until: Instant,
    val hasIndex: Boolean,
)

/** A copy of a person's home kept on the server, and what the model said it keeps. */
data class SandboxSnapshot(
    val id: String,
    val takenAt: Instant,
    val bytes: Long,
    val note: String?,
)

/**
 * A person's sandbox as the server reports it: whether it runs now, what it was given, how its last session
 * ended, when retention takes its files unless it is used first, and the snapshots it keeps.
 */
data class SandboxStatus(
    val running: Boolean,
    val cpus: Double,
    val memoryMb: Int,
    val homeMb: Int,
    val lastUsedAt: Instant,
    val deleteAfter: Instant,
    // retention took the files while a site kept the sandbox; the next use starts an empty home
    val homeReleasedAt: Instant?,
    val lastSessionEnd: String?,
    val snapshots: List<SandboxSnapshot>,
    // how many the server keeps, or null when it did not say
    val maxSnapshots: Int?,
)

// other tools take a sandbox file as `sandbox:<path>`, and the model may write it so here too, where every
// path already is one.
internal fun String.sandboxPath(): String = removePrefix(SANDBOX_REFERENCE_PREFIX)
