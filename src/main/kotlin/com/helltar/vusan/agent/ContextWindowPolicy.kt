package com.helltar.vusan.agent

import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ToolDefinition
import kotlin.math.ceil

// bytes/3 overestimates typical latin text while staying usable for cyrillic, CJK, emoji, JSON and code. real
// tokenizers differ, so [ContextWindowPolicy] keeps a separate safety reserve.
internal const val ESTIMATED_BYTES_PER_TOKEN = 3

// the widest character this bot handles: cyrillic is two bytes in UTF-8, latin one. turns a token budget into
// a character cap that does not double on cyrillic.
private const val ESTIMATED_BYTES_PER_CHAR = 2

internal fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0

    return ceil(text.encodeToByteArray().size.toDouble() / ESTIMATED_BYTES_PER_TOKEN).toInt().coerceAtLeast(1)
}

data class ContextTokenBudget(
    val contextWindowTokens: Int,
    val fixedPromptTokens: Int,
    val responseReserveTokens: Int,
    val agentReserveTokens: Int,
    val safetyReserveTokens: Int,
    val conversationTokens: Int,
) {

    /** Share of the window a turn occupies once its history costs [conversationTokens]. */
    fun contextPercentFor(conversationTokens: Int): Int =
        (((fixedPromptTokens + conversationTokens).toLong() * 100L) / contextWindowTokens).toInt().coerceIn(0, 100)
}

class ContextWindowPolicy(model: LlmModel) {

    val contextWindowTokens: Int =
        requireNotNull(model.contextWindowTokens) { "${model.id} has no context window to plan a turn against" }
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

    // room a run may grow into: tool results, retries and the nudge exchange. it bounds what one run piles up
    // (every iteration re-sends the pile), not what the window holds, so it absorbs several full-length tool
    // results rather than a share of a million-token window.
    private val agentReserveTokens: Int = (contextWindowTokens / 4).coerceIn(1_024, 64_000)

    // all tool results of one run must fit the agent reserve, counted in the same estimated tokens as the
    // rest of the budget. counting characters made a cyrillic result cost the run half what it cost the
    // prompt; a fixed ceiling would starve a large-window model, where one YouTube transcript would take the
    // whole run.
    val liveToolResultMaxTokens: Int = agentReserveTokens

    // the same reserve in characters, for tools that render to a size before anything can estimate it;
    // assumes the widest character, so it holds for cyrillic too.
    val liveToolResultMaxChars: Int = agentReserveTokens * ESTIMATED_BYTES_PER_TOKEN / ESTIMATED_BYTES_PER_CHAR

    // [tools] is what the request carries, which on a `ToolCatalog` is less than the catalog holds: a group
    // loaded mid-run is spent from the agent reserve, and counting every deferred schema here would give the
    // history a budget it never gets back.
    fun budget(systemPrompt: String, currentTurn: String, tools: List<ToolDefinition>): ContextTokenBudget {
        // the same estimate the fold uses, so the two never disagree on the ceiling
        val fixedPromptTokens =
            estimateTokens(systemPrompt) +
                    estimateTokens(currentTurn) +
                    estimateTokens(tools) +
                    FIXED_MESSAGE_OVERHEAD_TOKENS

        val responseReserve = (contextWindowTokens / 8).coerceIn(512, 8_192)
        val safetyReserve = (contextWindowTokens / 20).coerceAtLeast(256)
        val conversationTokens =
            (contextWindowTokens - fixedPromptTokens - responseReserve - agentReserveTokens - safetyReserve)
                .coerceAtLeast(0)

        return ContextTokenBudget(
            contextWindowTokens = contextWindowTokens,
            fixedPromptTokens = fixedPromptTokens,
            responseReserveTokens = responseReserve,
            agentReserveTokens = agentReserveTokens,
            safetyReserveTokens = safetyReserve,
            conversationTokens = conversationTokens,
        )
    }

    companion object {
        private const val FIXED_MESSAGE_OVERHEAD_TOKENS = 64
    }
}
