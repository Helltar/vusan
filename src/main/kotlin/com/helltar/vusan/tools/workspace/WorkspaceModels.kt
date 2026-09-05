package com.helltar.vusan.tools.workspace

import com.helltar.vusan.request.RequestContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// telegram's GroupAnonymousBot and Channel_Bot: one account standing in for many different senders.
private val SHARED_SENDER_IDS = setOf(1_087_968_824L, 136_817_688L)

/**
 * A person's files follow them across chats; conversation history remains chat-scoped. Senders that
 * Telegram delivers under a shared bot account get no workspace at all, since theirs would be one home
 * that every anonymous admin and every linked channel writes into.
 */
fun workspaceIdOrNull(context: RequestContext): String? =
    context.userId.takeUnless { it in SHARED_SENDER_IDS }?.let { "u$it" }

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
