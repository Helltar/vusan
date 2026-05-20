package com.helltar.vusan.tools.currency

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*

class ExchangeRateClient(private val http: HttpClient) {

    suspend fun latest(base: String): ExchangeRateResponse {
        val body: ExchangeRateResponse = http.get("https://open.er-api.com/v6/latest/${base.uppercase()}").body()
        check(body.result == "success") { "API error: ${body.result}" }
        return body
    }
}
