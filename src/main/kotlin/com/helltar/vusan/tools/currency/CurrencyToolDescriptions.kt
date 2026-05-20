package com.helltar.vusan.tools.currency

internal object CurrencyToolDescriptions {

    const val GET_EXCHANGE_RATE =
        "Returns the current exchange rate of one ISO-4217 currency against another, for example `USD` to `UAH`. " +
                "Use when the user asks about currency rates, conversions, or FX."

    const val BASE =
        "Base currency ISO-4217 code, e.g. `USD`."

    const val TARGET =
        "Target currency ISO-4217 code, e.g. `UAH`."
}
