package com.helltar.vusan.tools.sandbox

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*

class SandboxClient(private val http: HttpClient, baseUrl: String) {

    private val runUrl = "${baseUrl.trimEnd('/')}/run"

    suspend fun run(code: String): RunResponse {
        require(code.isNotBlank()) { "Code must not be blank" }

        return http.post(runUrl) {
            contentType(ContentType.Application.Json)
            setBody(RunRequest(code))
            // The service may wait for a free worker and then run up to its own
            // limit, so allow more than the shared client's default request timeout.
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }.body()
    }

    private companion object {
        // Must exceed the service's worst case: acquire wait (30s) + run cap (30s).
        const val REQUEST_TIMEOUT_MS = 75_000L
    }
}
