package com.helltar.vusan.tools.context

import com.helltar.vusan.agent.TurnToolBudget
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class ContextToolsTest {

    @Test
    fun `a fresh turn is told it can afford a long read`() = runBlocking {
        val result = ContextTools(TurnToolBudget(16_000)).checkContextBudget()

        assertContains(result, "16000 of 16000 tokens left for tool results in this turn (100%)")
        assertContains(result, "room for a long read")
    }

    @Test
    fun `a turn running low is told to narrow what it reads`() = runBlocking {
        val budget = TurnToolBudget(16_000)
        budget.spend(15_000)

        val result = ContextTools(budget).checkContextBudget()

        assertContains(result, "1000 of 16000 tokens left for tool results in this turn (6%)")
        assertContains(result, "Narrow what you read")
    }

    // past this point every further call comes back empty, so the only useful move is to answer
    @Test
    fun `a spent turn is told to answer from what it has`() = runBlocking {
        val budget = TurnToolBudget(16_000)
        budget.spend(16_000)

        val result = ContextTools(budget).checkContextBudget()

        assertContains(result, "0 of 16000 tokens left")
        assertContains(result, "answer now from what you already have")
        assertFalse("room for a long read" in result)
    }
}
