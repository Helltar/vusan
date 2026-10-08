package com.helltar.vusan.tools.context

import com.helltar.vusan.tools.LLMDescription
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.agent.TurnToolBudget
import com.helltar.vusan.agent.report
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class ContextTools(private val budget: TurnToolBudget) : ToolSet {

    @Tool
    @LLMDescription(ContextToolDescriptions.CHECK_CONTEXT_BUDGET)
    suspend fun checkContextBudget(): String = suspendToolGuard { budget.report() }
}
