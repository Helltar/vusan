package com.helltar.vusan.agent.history

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLModel

data class ContextTokenBudget(
    val contextWindowTokens: Int,
    val fixedPromptTokens: Int,
    val responseReserveTokens: Int,
    val agentReserveTokens: Int,
    val safetyReserveTokens: Int,
    val historyTokens: Int
) {
    val plannedInputTokens: Int
        get() = fixedPromptTokens + historyTokens

    val plannedContextPercent: Int
        get() = ((plannedInputTokens.toLong() * 100L) / contextWindowTokens).toInt().coerceIn(0, 100)
}

class ContextWindowPolicy(model: LLModel) {
    val contextWindowTokens: Int =
        (model.contextLength ?: DEFAULT_CONTEXT_WINDOW_TOKENS)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

    fun budget(
        systemPrompt: String,
        currentTime: String,
        currentTurn: String,
        toolRegistry: ToolRegistry
    ): ContextTokenBudget {
        val tools = toolRegistry.tools.joinToString("\n") { it.descriptor.toString() }
        val fixedPromptTokens =
            estimateHistoryTokens(systemPrompt) +
                    estimateHistoryTokens(currentTime) +
                    estimateHistoryTokens(currentTurn) +
                    estimateHistoryTokens(tools) +
                    toolRegistry.tools.size * TOOL_SCHEMA_OVERHEAD_TOKENS +
                    FIXED_MESSAGE_OVERHEAD_TOKENS

        val responseReserve = (contextWindowTokens / 8).coerceIn(512, 8_192)
        val agentReserve = (contextWindowTokens / 4).coerceIn(1_024, 16_384)
        val safetyReserve = (contextWindowTokens / 20).coerceAtLeast(256)
        val historyTokens =
            (contextWindowTokens - fixedPromptTokens - responseReserve - agentReserve - safetyReserve)
                .coerceAtLeast(0)

        return ContextTokenBudget(
            contextWindowTokens = contextWindowTokens,
            fixedPromptTokens = fixedPromptTokens,
            responseReserveTokens = responseReserve,
            agentReserveTokens = agentReserve,
            safetyReserveTokens = safetyReserve,
            historyTokens = historyTokens
        )
    }

    fun liveToolResultMaxChars(): Int =
        (agentReserveTokens() * 3 / 2).coerceIn(MIN_LIVE_TOOL_RESULT_CHARS, MAX_LIVE_TOOL_RESULT_CHARS)

    private fun agentReserveTokens(): Int =
        (contextWindowTokens / 4).coerceIn(1_024, 16_384)

    companion object {
        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 16_384L
        private const val TOOL_SCHEMA_OVERHEAD_TOKENS = 32
        private const val FIXED_MESSAGE_OVERHEAD_TOKENS = 64
        private const val MIN_LIVE_TOOL_RESULT_CHARS = 3_000
        private const val MAX_LIVE_TOOL_RESULT_CHARS = 24_000
    }
}
