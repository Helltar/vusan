package com.helltar.vusan.tools.workspace

import com.helltar.vusan.request.RequestContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A person's files stay separate between private conversations and each group. */
fun workspaceId(context: RequestContext): String =
    if (context.chatIsPrivate) "u${context.userId}"
    else "u${context.userId}_g${context.chatId.toString().trimStart('-')}"

@Serializable
data class CommandRequest(val command: String, val timeoutSeconds: Int? = null)

@Serializable
enum class CommandStatus {
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("timed_out") TIMED_OUT,
    @SerialName("cancelled") CANCELLED,
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("failed") FAILED
}

@Serializable
data class CommandResult(
    val jobId: String,
    val status: CommandStatus,
    val exitCode: Int? = null,
    val output: String = "",
    val nextOffset: Long = 0,
    val hasMore: Boolean = false,
    val truncated: Boolean = false,
    val elapsedMs: Long = 0,
    val diskWarning: Boolean = false,
    val error: String? = null
)

@Serializable
data class CommandList(val jobs: List<CommandResult> = emptyList())

@Serializable
data class WorkspaceError(val error: String)
