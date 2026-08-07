package com.helltar.vusan.config

import java.time.ZoneId

/**
 * A ceiling on the LLM tokens (input + output) the bot may spend in one day, `null` meaning no ceiling.
 *
 * The window is a calendar day in [zone], not a rolling 24 hours, because that is how provider allowances
 * actually work — OpenAI's free daily tokens roll over at 00:00 UTC — and because a fixed reset can be
 * named in the reply that turns a user away.
 *
 * [fairSharePercent] is how much of the day is first come, first served. Past that point one person can no
 * longer take the rest of it: whoever is already over `budget / recently active people` waits until the
 * reset while everyone below their share carries on. At `100` the day is never shared out this way.
 */
data class TokenBudgetConfig(
    val dailyTokens: Long? = null,
    val zone: ZoneId = DEFAULT_ZONE,
    val fairSharePercent: Int = DEFAULT_FAIR_SHARE_PERCENT
) {
    init {
        require(dailyTokens == null || dailyTokens > 0) { "LLM_DAILY_TOKEN_BUDGET must be positive" }

        require(fairSharePercent in 1..100) {
            "LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT must be between 1 and 100"
        }
    }

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("UTC")

        // most days never reach this, so nobody meets the per-person rule at all; what is left past it is
        // the part worth protecting from a single heavy user.
        const val DEFAULT_FAIR_SHARE_PERCENT = 70
    }
}
