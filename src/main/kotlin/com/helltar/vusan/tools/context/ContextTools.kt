package com.helltar.vusan.tools.context

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.TurnToolBudget
import com.helltar.vusan.agent.report
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class ContextTools(private val budget: TurnToolBudget) : ToolSet {

    @Tool
    @LLMDescription(ContextToolDescriptions.CHECK_CONTEXT_BUDGET)
    suspend fun checkContextBudget(): String = suspendToolGuard { budget.report() }
}
