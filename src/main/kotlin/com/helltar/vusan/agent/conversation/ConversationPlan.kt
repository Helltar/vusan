package com.helltar.vusan.agent.conversation

import com.helltar.vusan.agent.ESTIMATED_BYTES_PER_TOKEN
import com.helltar.vusan.agent.estimateTokens
import com.helltar.vusan.common.limitTo

private const val EXACT_TOOL_INTERACTIONS = 2
private const val MESSAGE_OVERHEAD_TOKENS = 12

data class PromptConversation(
    val summary: String?,
    val turns: List<ChatTurn>
)

data class ConversationPlan(
    val prompt: PromptConversation,
    val compactablePrefix: List<ConversationInteraction>,
    val estimatedTokens: Int,
    val includedInteractions: Int,
    val exactToolInteractions: Int,
    val stats: ConversationStats
)

fun planConversation(
    snapshot: ConversationSnapshot,
    tokenBudget: Int,
    maxRecentInteractions: Int
): ConversationPlan {
    require(maxRecentInteractions > 0) { "maxRecentInteractions must be positive" }

    val boundedBudget = tokenBudget.coerceAtLeast(0)
    val summary = snapshot.summary.fitToTokenBudget(boundedBudget)
    val summaryTokens = summary?.let(::estimateTokens) ?: 0
    var remaining = (boundedBudget - summaryTokens).coerceAtLeast(0)

    val eligible = snapshot.interactions.takeLast(maxRecentInteractions)
    val selected = ArrayDeque<List<ChatTurn>>()
    var exactToolInteractions = 0

    for ((distanceFromNewest, interaction) in eligible.asReversed().withIndex()) {
        val exactTools = distanceFromNewest < EXACT_TOOL_INTERACTIONS
        val turns =
            interaction.turns
                .validForReplay()
                .let { replayable -> if (exactTools) replayable else replayable.withoutToolEvents() }

        val estimated = estimateTokens(turns)

        if (estimated <= remaining) {
            selected.addFirst(turns)
            remaining -= estimated
            if (exactTools && turns.any { it.role == ChatRole.TOOL_CALL }) exactToolInteractions++
            continue
        }

        if (selected.isEmpty()) {
            val fitted = turns.withoutToolEvents().fitToTokenBudget(remaining)
            if (fitted.isNotEmpty()) {
                selected.addFirst(fitted)
                remaining -= estimateTokens(fitted)
            }
        }

        break
    }

    val includedCount = selected.size
    val omittedByBudget = snapshot.interactions.size - includedCount
    val retainedAfterCountCompaction = (maxRecentInteractions * 2 / 3).coerceAtLeast(1)
    val omittedByCount =
        if (snapshot.interactions.size > maxRecentInteractions)
            snapshot.interactions.size - retainedAfterCountCompaction
        else
            0
    val compactableCount = maxOf(omittedByBudget, omittedByCount)
    val turns = selected.flatten()
    val estimatedTokens = summaryTokens + estimateTokens(turns)

    return ConversationPlan(
        prompt = PromptConversation(summary = summary, turns = turns),
        compactablePrefix = snapshot.interactions.take(compactableCount),
        estimatedTokens = estimatedTokens,
        includedInteractions = includedCount,
        exactToolInteractions = exactToolInteractions,
        stats = snapshot.stats
    )
}

internal fun estimateTokens(turns: List<ChatTurn>): Int =
    turns.sumOf { turn ->
        MESSAGE_OVERHEAD_TOKENS +
                estimateTokens(turn.content) +
                estimateTokens(turn.toolName.orEmpty()) +
                estimateTokens(turn.toolCallId.orEmpty())
    }

private fun String?.fitToTokenBudget(tokenBudget: Int): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (estimateTokens(value) <= tokenBudget) return value

    return value.limitTo(tokenBudget * ESTIMATED_BYTES_PER_TOKEN).takeIf { it.isNotBlank() }
}

private fun List<ChatTurn>.fitToTokenBudget(tokenBudget: Int): List<ChatTurn> {
    if (tokenBudget <= MESSAGE_OVERHEAD_TOKENS) return emptyList()
    if (estimateTokens(this) <= tokenBudget) return this

    val humanTurns = withoutToolEvents()
    if (humanTurns.isEmpty()) return emptyList()

    var charsPerTurn =
        ((tokenBudget - humanTurns.size * MESSAGE_OVERHEAD_TOKENS) * ESTIMATED_BYTES_PER_TOKEN / humanTurns.size)
            .coerceAtLeast(0)

    while (charsPerTurn > 0) {
        val fitted = humanTurns.map { it.copy(content = it.content.limitTo(charsPerTurn)) }
        if (estimateTokens(fitted) <= tokenBudget) return fitted
        charsPerTurn = (charsPerTurn * 3) / 4
    }

    return emptyList()
}

private fun List<ChatTurn>.withoutToolEvents(): List<ChatTurn> =
    filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }

// providers require a matching result for every replayed tool call. legacy history may contain a
// row-level trim boundary, so discard only broken tool fragments while preserving human turns.
private fun List<ChatTurn>.validForReplay(): List<ChatTurn> =
    buildList {
        var index = 0

        while (index < this@validForReplay.size) {
            val turn = this@validForReplay[index]

            if (turn.role != ChatRole.TOOL_CALL) {
                if (turn.role != ChatRole.TOOL_RESULT) add(turn)
                index++
                continue
            }

            val result = this@validForReplay.getOrNull(index + 1)
            if (
                result?.role == ChatRole.TOOL_RESULT &&
                result.toolCallId == turn.toolCallId &&
                result.toolName == turn.toolName
            ) {
                add(turn)
                add(result)
                index += 2
            } else {
                index++
            }
        }
    }
