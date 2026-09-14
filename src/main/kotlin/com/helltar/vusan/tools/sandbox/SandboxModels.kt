package com.helltar.vusan.tools.sandbox

import kotlinx.serialization.Serializable

/*
 * the slice of the regolith /v1 api the bot uses. fields the server may grow new values for — an
 * outcome type, a reason — stay strings, so a newer server cannot break decoding here.
 */

@Serializable
internal data class ExecRequest(val shell: String, val timeoutSeconds: Int? = null)

@Serializable
internal data class ExecOutcome(val type: String, val exitCode: Int? = null, val reason: String? = null)

@Serializable
internal data class ExecInfo(
    val id: String,
    val status: String,
    val outcome: ExecOutcome? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val outputTruncated: Boolean = false,
)

@Serializable
internal data class ExecPage(val execs: List<ExecInfo> = emptyList())

@Serializable
internal data class OutputFrame(val kind: String, val text: String = "", val end: Long = 0)

@Serializable
internal data class OutputPage(
    val frames: List<OutputFrame> = emptyList(),
    val nextOffset: Long = 0,
    val complete: Boolean = false,
)

@Serializable
internal data class ServerInfo(val limits: ServerLimits, val publishing: Boolean = false)

@Serializable
internal data class PublishRequest(val path: String)

/** A published site, as the Regolith server reports it. */
@Serializable
data class PublishedSite(
    val site: String,
    val url: String,
    val release: String,
    val files: Int = 0,
    val bytes: Long = 0,
    val publishedAt: String? = null,
)

@Serializable
internal data class DirectoryListing(val entries: List<DirectoryEntry> = emptyList())

@Serializable
internal data class DirectoryEntry(val name: String, val type: String)

@Serializable
internal data class ServerLimits(val maxExecTimeoutSeconds: Int = 0)

/** An error response: an RFC 9457 problem document whose `code` is the stable member to branch on. */
@Serializable
internal data class ProblemDetails(val code: String = "", val title: String = "", val detail: String = "")

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
