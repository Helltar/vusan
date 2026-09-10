package com.helltar.vusan.tools.context

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.TurnToolBudget
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class ContextTools(private val budget: TurnToolBudget) : ToolSet {

    private companion object {
        // below this the next long read is the one that starts losing its own tail
        const val LOW_PERCENT = 25
    }

    @Tool
    @LLMDescription(ContextToolDescriptions.CHECK_CONTEXT_BUDGET)
    suspend fun checkContextBudget(): String = suspendToolGuard {
        val left = budget.remainingTokens
        val percent = budget.percentLeft

        buildString {
            append("$left of ${budget.totalTokens} tokens left for tool results in this turn ($percent%). ")

            when {
                left <= 0 ->
                    append("Further results arrive empty — answer now from what you already have.")

                percent <= LOW_PERCENT ->
                    append(
                        "Narrow what you read from here on — a range, a page, a shorter window — and finish soon: " +
                                "past the budget results arrive truncated, then empty."
                    )

                else ->
                    append("There is room for a long read; anything past the budget arrives truncated.")
            }
        }
    }
}
