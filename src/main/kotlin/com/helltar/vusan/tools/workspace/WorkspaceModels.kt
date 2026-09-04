package com.helltar.vusan.tools.workspace

import com.helltar.vusan.request.RequestContext
import kotlinx.serialization.Serializable

/**
 * Which workspace a turn belongs to: one person in one chat, the same key the raw conversation
 * history uses. A group therefore gives every member a workspace of their own rather than one
 * the whole chat can read, and the same person's private project stays invisible from a group.
 */
fun workspaceId(context: RequestContext): String =
    if (context.chatIsPrivate) "u${context.userId}"
    // the leading minus of a group chat id would read as a flag wherever the id becomes an argument
    else "u${context.userId}_g${context.chatId.toString().trimStart('-')}"

@Serializable
data class ExecRequest(val workspaceId: String, val command: String, val timeoutSeconds: Int? = null)

@Serializable
data class ExecResponse(
    val exitCode: Int = 0,
    val timedOut: Boolean = false,
    val stdout: String = "",
    val stdoutTruncated: Boolean = false,
    val stderr: String = "",
    val stderrTruncated: Boolean = false,
    val elapsedMs: Long = 0,
    val logPath: String? = null,
    val usedBytes: Long = 0,
    val quotaBytes: Long = 0,
    // set instead of a result when the service refuses the run (busy, at capacity, out of space)
    val error: String? = null
)

@Serializable
data class ListResponse(
    val entries: List<WorkspaceEntry> = emptyList(),
    val usedBytes: Long = 0,
    val quotaBytes: Long = 0,
    val error: String? = null
)

@Serializable
data class WorkspaceEntry(val path: String, val bytes: Long = 0, val dir: Boolean = false)
