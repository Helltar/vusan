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

    private companion object {
        // below this the next long read is the one that starts losing its own tail
        const val LOW_PERCENT = 25
    }

    @Volatile
    var remainingTokens: Int = totalTokens
        private set

    val percentLeft: Int
        get() = if (totalTokens <= 0) 0 else (remainingTokens.toLong() * 100 / totalTokens).toInt()

    val isLow: Boolean
        get() = percentLeft <= LOW_PERCENT

    fun spend(tokens: Int) {
        remainingTokens = (remainingTokens - tokens).coerceAtLeast(0)
    }
}

/**
 * The figures and what to do about them, in one wording for both surfaces that state them: the
 * `checkContextBudget` tool when the model asks, and the run's own notice when it does not.
 */
internal fun TurnToolBudget.report(): String =
    buildString {
        append("$remainingTokens of $totalTokens tokens left for tool results in this turn ($percentLeft%). ")

        when {
            remainingTokens <= 0 ->
                append("Further results arrive empty — answer now from what you already have.")

            isLow ->
                append(
                    "Narrow what you read from here on — a range, a page, a shorter window — and deliver your " +
                            "answer soon: past the budget results arrive truncated, then empty."
                )

            else ->
                append("There is room for a long read; anything past the budget arrives truncated.")
        }
    }
