package com.helltar.vusan.agent

/**
 * What one run may still pile up in tool results, in estimated tokens.
 *
 * `ContextWindowPolicy` sizes the reserve per model and the run spends it as results return. It is the part
 * of the budget the model moves itself, and the one that bites: past it results are truncated, then omitted,
 * so a turn can keep calling tools and learn nothing. `checkContextBudget` reports it.
 *
 * Read-only calls of a batch run in parallel, so spending is serialized; the field is read across coroutine
 * hops between calls.
 */
class TurnToolBudget(val totalTokens: Int) {

    @Volatile
    var remainingTokens: Int = totalTokens
        private set

    val percentLeft: Int
        get() = if (totalTokens <= 0) 0 else (remainingTokens.toLong() * 100 / totalTokens).toInt()

    val isLow: Boolean
        get() = percentLeft <= LOW_PERCENT

    fun spend(tokens: Int) {
        synchronized(this) { remainingTokens = (remainingTokens - tokens).coerceAtLeast(0) }
    }

    private companion object {
        // below this the next long read starts losing its tail
        const val LOW_PERCENT = 25
    }
}

/**
 * The figures and what to do about them, worded once for both places that state them: the
 * `checkContextBudget` tool, and the run's own unprompted notice.
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
                            "answer soon: past the budget results arrive truncated, then empty.",
                )

            else ->
                append("There is room for a long read; anything past the budget arrives truncated.")
        }
    }
