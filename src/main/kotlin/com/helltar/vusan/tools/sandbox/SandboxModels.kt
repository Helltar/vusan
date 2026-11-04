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

// other tools take a sandbox file as `sandbox:<path>`, and the model may write it so here too, where every
// path already is one.
internal fun String.sandboxPath(): String = removePrefix(SANDBOX_REFERENCE_PREFIX)
