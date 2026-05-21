package com.helltar.vusan.infra

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.statement.*
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
                    throw HttpStatusException(response.status.value, response.call.request.url.host, response.bodyAsTextSafe())
                }
            }

            handleResponseExceptionWithRequest { cause, request ->
                if (cause is ResponseException) {
                    throw HttpStatusException(cause.response.status.value, request.url.host, cause.response.bodyAsTextSafe())
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

private const val ERROR_BODY_PREVIEW_LIMIT = 1_000

private class HttpStatusException(status: Int, host: String, body: String?) :
    IllegalStateException(buildMessage(status, host, body)) {

    companion object {
        private fun buildMessage(status: Int, host: String, body: String?): String {
            val base = "HTTP $status from $host"
            val preview = body?.trim().orEmpty()
            if (preview.isEmpty()) return base
            val capped = if (preview.length > ERROR_BODY_PREVIEW_LIMIT) {
                preview.take(ERROR_BODY_PREVIEW_LIMIT) + "…"
            } else {
                preview
            }
            return "$base: $capped"
        }
    }
}

private suspend fun HttpResponse.bodyAsTextSafe(): String? =
    runCatching { bodyAsText() }.getOrNull()
