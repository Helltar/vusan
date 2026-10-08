package com.helltar.vusan.tools.currency

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.suspendToolGuard

class CurrencyTools(private val client: ExchangeRateClient) : ToolSet {

    @Tool(CurrencyToolDescriptions.GET_EXCHANGE_RATE)
    suspend fun getExchangeRate(
        @Arg(CurrencyToolDescriptions.BASE)
        base: String,
        @Arg(CurrencyToolDescriptions.TARGET)
        target: String,
    ): String = suspendToolGuard {
        val response = client.latest(base)
        val rate = response.rates[target.uppercase()] ?: return@suspendToolGuard "Unknown currency: $target"
        "1 ${response.baseCode} = $rate ${target.uppercase()} (as of ${response.timeLastUpdateUtc ?: "unknown"})"
    }
}
