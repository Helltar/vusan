package com.helltar.vusan.agent

/**
 * What one run may still pile up in tool results, in estimated tokens.
 *
 * `ContextWindowPolicy` sizes the reserve per model; the run spends it as results come back. It is the
 * one part of the budget the model itself moves, and the one that bites: past it a result is truncated
 * and then omitted entirely, so a turn can keep calling tools and learn nothing from them. The
 * strategy spends it, `checkContextBudget` reports it.
 *
 * A turn executes its tool calls one after another, so the field only has to be visible across the
 * coroutine hops between them, not atomic against a concurrent spender.
 */
class TurnToolBudget(val totalTokens: Int) {

    @Volatile
    var remainingTokens: Int = totalTokens
        private set

    val percentLeft: Int
        get() = if (totalTokens <= 0) 0 else (remainingTokens.toLong() * 100 / totalTokens).toInt()

    fun spend(tokens: Int) {
        remainingTokens = (remainingTokens - tokens).coerceAtLeast(0)
    }
}
