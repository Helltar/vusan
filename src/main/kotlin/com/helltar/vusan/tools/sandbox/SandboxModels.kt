package com.helltar.vusan.tools.sandbox

/** How a command ended, as the tools describe it to the model. */
enum class CommandStatus { RUNNING, COMPLETED, TIMED_OUT, CANCELLED, INTERRUPTED }

/** A session limit that explains a failed command; the sandbox as a whole hit it, not one process. */
enum class CommandLimit { OUT_OF_MEMORY, TOO_MANY_PROCESSES }

/**
 * One command as the model sees it: its id to poll with, what came out of it so far, and how far the
 * next read should start.
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
    /** Why a session ended under the command, as the server named it; only for [CommandStatus.INTERRUPTED]. */
    val reason: String? = null,
)
