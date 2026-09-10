package com.helltar.vusan.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class TurnToolBudgetTest {

    @Test
    fun `spending draws the reserve down`() {
        val budget = TurnToolBudget(1_000)

        budget.spend(250)

        assertEquals(750, budget.remainingTokens)
        assertEquals(75, budget.percentLeft)
    }

    // the last result is bounded to what is left, but it is charged at its real estimate, which can
    // overshoot — the budget floors instead of going negative and reading as a fresh one.
    @Test
    fun `an overspend stops at zero`() {
        val budget = TurnToolBudget(1_000)

        budget.spend(1_500)

        assertEquals(0, budget.remainingTokens)
        assertEquals(0, budget.percentLeft)
    }

    // the startup probe builds a catalog with no run behind it
    @Test
    fun `a budget of nothing reports nothing rather than dividing by zero`() {
        assertEquals(0, TurnToolBudget(0).percentLeft)
    }
}
