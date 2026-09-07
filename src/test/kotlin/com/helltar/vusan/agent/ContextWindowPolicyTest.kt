package com.helltar.vusan.agent

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
                currentTurn = "current request",
                toolRegistry = ToolRegistry.EMPTY
            )

        assertEquals(8_192, budget.contextWindowTokens)
        assertTrue(budget.responseReserveTokens >= 1_024)
        assertTrue(budget.agentReserveTokens >= 2_048)
        assertTrue(budget.safetyReserveTokens >= 256)
        assertTrue(
            budget.fixedPromptTokens +
                    budget.conversationTokens +
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

    @Test
    fun `live tool result budget grows with the context window`() {
        val small = ContextWindowPolicy(model(contextLength = 16_384)).liveToolResultMaxTokens
        val medium = ContextWindowPolicy(model(contextLength = 128_000)).liveToolResultMaxTokens
        val large = ContextWindowPolicy(model(contextLength = 400_000)).liveToolResultMaxTokens

        assertTrue(medium > small, "budget did not grow: small=$small medium=$medium")
        assertTrue(large > medium, "budget stopped growing: medium=$medium large=$large")

        // a research turn reads several sources, so a large window has to absorb more than a couple
        // of full-length tool results before later ones start coming back truncated.
        val transcript = estimateTokens("x".repeat(MAX_TRANSCRIPT_CHARS))
        assertTrue(large > 6 * transcript, "a few transcripts exhaust the run budget: $large")
    }

    @Test
    fun `the live tool result budget is the agent reserve itself`() {
        val policy = ContextWindowPolicy(model(contextLength = 100_000))

        val budget =
            policy.budget(
                systemPrompt = "system",
                currentTurn = "current request",
                toolRegistry = ToolRegistry.EMPTY
            )

        assertEquals(budget.agentReserveTokens, policy.liveToolResultMaxTokens)
        assertTrue(policy.liveToolResultMaxChars < budget.agentReserveTokens * ESTIMATED_BYTES_PER_TOKEN)
    }

    private fun model(contextLength: Long?): LLModel =
        LLModel(provider = LLMProvider.OpenAI, id = "test", contextLength = contextLength)
}

// the tool caps its own output at this size, so it is the worst single result the run budget must absorb.
private const val MAX_TRANSCRIPT_CHARS = 24_000
