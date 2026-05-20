package com.helltar.vusan.infra

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object Http {

    val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun createClient(): HttpClient =
        HttpClient(CIO) {
            configure()
        }

    fun createClient(engine: HttpClientEngine): HttpClient =
        HttpClient(engine) {
            configure()
        }

    private fun HttpClientConfig<*>.configure() {
        expectSuccess = true

        HttpResponseValidator {
            validateResponse { response ->
                if (response.status.value !in 200..299) {
                    throw HttpStatusException(response.status.value, response.call.request.url.host)
                }
            }

            handleResponseExceptionWithRequest { cause, request ->
                if (cause is ResponseException) {
                    throw HttpStatusException(cause.response.status.value, request.url.host)
                }
            }
        }

        install(ContentNegotiation) { json(json) }

        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
    }
}

private class HttpStatusException(status: Int, host: String) : IllegalStateException("HTTP $status from $host")
