package com.helltar.vusan.agent.history

import com.helltar.vusan.common.collapseWhitespaceAndCap

private const val PROMPT_RECENT_TURNS = 12
private const val MAX_SUMMARY_CHARS = 2_000
private const val MAX_TURN_SNIPPET_CHARS = 240

data class PromptHistory(
    val summary: String?,
    val turns: List<ChatTurn>
)

fun summarizeForPrompt(history: List<ChatTurn>): PromptHistory {
    if (history.size <= PROMPT_RECENT_TURNS) {
        return PromptHistory(summary = null, turns = history)
    }

    val sliceStart = history.recentSliceStart()

    val olderTurns = history.subList(0, sliceStart)
    val recentTurns = history.subList(sliceStart, history.size)

    return PromptHistory(
        summary = buildSummary(olderTurns),
        turns = recentTurns
    )
}

// anchor the slice to a USER turn — a leading orphan TOOL_CALL/TOOL_RESULT (owner trimmed away) is
// rejected by the provider. search back, then forward; if the window has no USER, fold all to summary.
private fun List<ChatTurn>.recentSliceStart(): Int {
    val initial = (size - PROMPT_RECENT_TURNS).coerceAtLeast(0)

    for (idx in initial downTo 0) {
        if (this[idx].role == ChatRole.USER) return idx
    }

    for (idx in initial + 1 until size) {
        if (this[idx].role == ChatRole.USER) return idx
    }

    return size
}

private fun buildSummary(turns: List<ChatTurn>): String? {
    if (turns.isEmpty()) {
        return null
    }

    val lines = mutableListOf<String>()
    var usedChars = 0

    for (turn in turns.asReversed()) {
        val role =
            when (turn.role) {
                ChatRole.USER -> "User"
                ChatRole.ASSISTANT -> "Assistant"
                ChatRole.TOOL_CALL, ChatRole.TOOL_RESULT -> continue
            }

        val snippet = turn.content.collapseWhitespaceAndCap(MAX_TURN_SNIPPET_CHARS) ?: continue

        val line = "- $role: $snippet"
        val projectedSize = usedChars + line.length + if (lines.isEmpty()) 0 else 1

        if (projectedSize > MAX_SUMMARY_CHARS) {
            continue
        }

        lines += line
        usedChars = projectedSize
    }

    if (lines.isEmpty()) {
        return null
    }

    return lines.asReversed().joinToString("\n")
}
