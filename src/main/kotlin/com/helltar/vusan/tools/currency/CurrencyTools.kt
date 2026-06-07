package com.helltar.vusan.tools.currency

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class CurrencyTools(private val client: ExchangeRateClient) : ToolSet {

    @Tool
    @LLMDescription(CurrencyToolDescriptions.GET_EXCHANGE_RATE)
    suspend fun getExchangeRate(
        @LLMDescription(CurrencyToolDescriptions.BASE)
        base: String,
        @LLMDescription(CurrencyToolDescriptions.TARGET)
        target: String
    ): String = suspendToolGuard {
        val response = client.latest(base)
        val rate = response.rates[target.uppercase()] ?: return@suspendToolGuard "Unknown currency: $target"
        "1 ${response.baseCode} = $rate ${target.uppercase()} (as of ${response.timeLastUpdateUtc ?: "unknown"})"
    }
}
