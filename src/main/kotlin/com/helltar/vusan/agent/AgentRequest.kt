package com.helltar.vusan.agent

import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.ToolGroup

/**
 * One turn to run: its origin and its two texts. [prompt] is what the model sees, [conversationEntry] what
 * history keeps: the prompt carries context the stored exchange need not repeat. [replyPrivately] sends the
 * whole answer to the person's private chat from the first output on, as `replyInPrivateMessages` does once
 * the model calls it. [toolGroups] are offered from the first request, for a turn whose input already says
 * which tools answer it.
 */
data class AgentRequest(
    val context: RequestContext,
    val prompt: String,
    val conversationEntry: String,
    val replyPrivately: Boolean = false,
    val toolGroups: Set<ToolGroup> = emptySet(),
)

data class AgentResult(
    val outputs: List<OutboxItem>,
    val comment: String?,
    val commentToPrivate: Boolean = false,
    // the run failed without an answer: `comment` is the canned failure reply, and `outputs` may hold what
    // the turn posted before breaking, which delivery records without resending.
    val failed: Boolean = false,
)
