package com.helltar.vusan.agent.history

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextWindowPolicyTest {

    @Test
    fun `budget reserves output agent growth and safety before history`() {
        val policy = ContextWindowPolicy(model(contextLength = 8_192))

        val budget =
            policy.budget(
                systemPrompt = "system",
                currentTime = "time",
                currentTurn = "current request",
                toolRegistry = ToolRegistry.EMPTY
            )

        assertEquals(8_192, budget.contextWindowTokens)
        assertTrue(budget.responseReserveTokens >= 1_024)
        assertTrue(budget.agentReserveTokens >= 2_048)
        assertTrue(budget.safetyReserveTokens >= 256)
        assertTrue(
            budget.fixedPromptTokens +
                    budget.historyTokens +
                    budget.responseReserveTokens +
                    budget.agentReserveTokens +
                    budget.safetyReserveTokens <= budget.contextWindowTokens
        )
    }

    @Test
    fun `unknown compatible model gets a conservative default window`() {
        val policy = ContextWindowPolicy(model(contextLength = null))

        assertEquals(16_384, policy.contextWindowTokens)
    }

    private fun model(contextLength: Long?): LLModel =
        LLModel(provider = LLMProvider.OpenAI, id = "test", contextLength = contextLength)
}
