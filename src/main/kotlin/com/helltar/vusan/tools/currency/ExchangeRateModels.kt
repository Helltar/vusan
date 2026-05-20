package com.helltar.vusan.tools.currency

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExchangeRateResponse(

    val result: String,

    @SerialName("base_code")
    val baseCode: String,

    @SerialName("time_last_update_utc")
    val timeLastUpdateUtc: String? = null,

    val rates: Map<String, Double> = emptyMap()
)
