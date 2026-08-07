package com.helltar.vusan.config

import java.time.ZoneId

/**
 * A ceiling on the LLM tokens (input + output) the bot may spend in one day, `null` meaning no ceiling.
 *
 * The window is a calendar day in [zone], not a rolling 24 hours, because that is how provider allowances
 * actually work — OpenAI's free daily tokens roll over at 00:00 UTC — and because a fixed reset can be
 * named in the reply that turns a user away.
 */
data class TokenBudgetConfig(
    val dailyTokens: Long? = null,
    val zone: ZoneId = DEFAULT_ZONE
) {
    init {
        require(dailyTokens == null || dailyTokens > 0) { "LLM_DAILY_TOKEN_BUDGET must be positive" }
    }

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("UTC")
    }
}
