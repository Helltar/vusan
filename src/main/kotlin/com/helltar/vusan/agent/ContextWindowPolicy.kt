package com.helltar.vusan.agent

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLModel
import kotlin.math.ceil

// bytes/3 deliberately overestimates typical latin text while staying useful for cyrillic, CJK,
// emoji, JSON, and code. exact provider tokenizers differ, so [ContextWindowPolicy] also keeps a
// separate safety reserve.
internal const val ESTIMATED_BYTES_PER_TOKEN = 3

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
    val conversationTokens: Int
) {
    /** Share of the window a turn occupies once its history actually costs [conversationTokens]. */
    fun contextPercentFor(conversationTokens: Int): Int =
        (((fixedPromptTokens + conversationTokens).toLong() * 100L) / contextWindowTokens).toInt().coerceIn(0, 100)
}

class ContextWindowPolicy(model: LLModel) {

    companion object {
        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 16_384L
        private const val TOOL_SCHEMA_OVERHEAD_TOKENS = 32
        private const val FIXED_MESSAGE_OVERHEAD_TOKENS = 64
    }

    val contextWindowTokens: Int =
        (model.contextLength ?: DEFAULT_CONTEXT_WINDOW_TOKENS)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

    // room the agent may grow into during a run: tool results, retries, and the nudge exchange.
    // the ceiling bounds what one run may pile up, not what the window can hold — every later
    // iteration re-sends the whole pile — so it is set to absorb several full-length tool results
    // rather than to a share of a million-token window.
    private val agentReserveTokens: Int = (contextWindowTokens / 4).coerceIn(1_024, 64_000)

    // everything the tools return during one run has to fit the agent reserve, so the reserve is
    // converted back to characters at the same ratio [estimateTokens] reads them. A fixed
    // ceiling here would silently starve a large-window model: one full-length YouTube transcript
    // would consume the whole run and leave later tool results with nothing.
    val liveToolResultMaxChars: Int = agentReserveTokens * ESTIMATED_BYTES_PER_TOKEN

    fun budget(
        systemPrompt: String,
        trailingSystemContext: String,
        currentTurn: String,
        toolRegistry: ToolRegistry
    ): ContextTokenBudget {
        val tools = toolRegistry.tools.joinToString("\n") { it.descriptor.toString() }
        val fixedPromptTokens =
            estimateTokens(systemPrompt) +
                    estimateTokens(trailingSystemContext) +
                    estimateTokens(currentTurn) +
                    estimateTokens(tools) +
                    toolRegistry.tools.size * TOOL_SCHEMA_OVERHEAD_TOKENS +
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
            conversationTokens = conversationTokens
        )
    }
}
