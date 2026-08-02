package com.helltar.vusan.agent.history

import com.helltar.vusan.common.limitTo
import kotlin.math.ceil

// bytes/3 deliberately overestimates typical latin text while staying useful for cyrillic, CJK,
// emoji, JSON, and code. exact provider tokenizers differ, so ContextWindowPolicy also keeps a
// separate safety reserve.
internal const val ESTIMATED_BYTES_PER_TOKEN = 3

private const val EXACT_TOOL_INTERACTIONS = 2
private const val MESSAGE_OVERHEAD_TOKENS = 12

data class PromptHistory(
    val summary: String?,
    val turns: List<ChatTurn>
)

data class HistoryPromptPlan(
    val history: PromptHistory,
    val compactablePrefix: List<ChatInteraction>,
    val estimatedTokens: Int,
    val includedInteractions: Int,
    val exactToolInteractions: Int,
    val stats: ChatHistoryStats
)

fun planHistoryForPrompt(
    snapshot: ChatHistorySnapshot,
    tokenBudget: Int,
    maxRecentInteractions: Int
): HistoryPromptPlan {
    require(maxRecentInteractions > 0) { "maxRecentInteractions must be positive" }

    val boundedBudget = tokenBudget.coerceAtLeast(0)
    val summary = snapshot.summary.fitToTokenBudget(boundedBudget)
    val summaryTokens = summary?.let(::estimateHistoryTokens) ?: 0
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

        val estimated = estimateHistoryTokens(turns)

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
                remaining -= estimateHistoryTokens(fitted)
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
    val estimatedTokens = summaryTokens + estimateHistoryTokens(turns)

    return HistoryPromptPlan(
        history = PromptHistory(summary = summary, turns = turns),
        compactablePrefix = snapshot.interactions.take(compactableCount),
        estimatedTokens = estimatedTokens,
        includedInteractions = includedCount,
        exactToolInteractions = exactToolInteractions,
        stats = snapshot.stats
    )
}

internal fun estimateHistoryTokens(text: String): Int {
    if (text.isEmpty()) return 0

    return ceil(text.encodeToByteArray().size.toDouble() / ESTIMATED_BYTES_PER_TOKEN).toInt().coerceAtLeast(1)
}

internal fun estimateHistoryTokens(turns: List<ChatTurn>): Int =
    turns.sumOf { turn ->
        MESSAGE_OVERHEAD_TOKENS +
                estimateHistoryTokens(turn.content) +
                estimateHistoryTokens(turn.toolName.orEmpty()) +
                estimateHistoryTokens(turn.toolCallId.orEmpty())
    }

private fun String?.fitToTokenBudget(tokenBudget: Int): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (estimateHistoryTokens(value) <= tokenBudget) return value

    return value.limitTo(tokenBudget * ESTIMATED_BYTES_PER_TOKEN).takeIf { it.isNotBlank() }
}

private fun List<ChatTurn>.fitToTokenBudget(tokenBudget: Int): List<ChatTurn> {
    if (tokenBudget <= MESSAGE_OVERHEAD_TOKENS) return emptyList()
    if (estimateHistoryTokens(this) <= tokenBudget) return this

    val humanTurns = withoutToolEvents()
    if (humanTurns.isEmpty()) return emptyList()

    var charsPerTurn =
        ((tokenBudget - humanTurns.size * MESSAGE_OVERHEAD_TOKENS) * ESTIMATED_BYTES_PER_TOKEN / humanTurns.size)
            .coerceAtLeast(0)

    while (charsPerTurn > 0) {
        val fitted = humanTurns.map { it.copy(content = it.content.limitTo(charsPerTurn)) }
        if (estimateHistoryTokens(fitted) <= tokenBudget) return fitted
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
