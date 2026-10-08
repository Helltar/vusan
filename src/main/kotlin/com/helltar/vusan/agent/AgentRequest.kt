package com.helltar.vusan.agent

import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.request.RequestContext

/**
 * One turn to run: where it came from, and the two texts it is made of.
 *
 * [prompt] is what the model is shown and [conversationEntry] what history keeps, which are not the
 * same string — a turn's prompt carries context the stored exchange has no reason to repeat.
 */
data class AgentRequest(
    val context: RequestContext,
    val prompt: String,
    val conversationEntry: String,
)

data class AgentResult(
    val outputs: List<OutboxItem>,
    val comment: String?,
    val commentToPrivate: Boolean = false,
    // the run ended in an error and produced no answer: `comment` is the canned failure reply. `outputs`
    // may still hold what the turn put in the chat before it broke, which delivery records without
    // sending again.
    val failed: Boolean = false,
)
