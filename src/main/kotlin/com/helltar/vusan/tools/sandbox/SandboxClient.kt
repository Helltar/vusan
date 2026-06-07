package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SandboxClient(private val http: HttpClient, baseUrl: String, runTimeout: Duration) {

    private companion object {
        // Mirrors the sandbox service's ACQUIRE_TIMEOUT_SECONDS — the longest it
        // waits for a free worker before giving up with a 503.
        val ACQUIRE_BUDGET = 30.seconds
        val NETWORK_SLACK = 15.seconds

        // Returned when the sandbox service can't be reached at all (container not
        // running, wrong URL) — distinct from a Python error, which comes back in
        // RunResponse.error. Framed so the model stops instead of rewriting code.
        const val UNREACHABLE_MESSAGE =
            "Code execution is not reachable right now, so the code did not run. " +
                    "Tell the user code execution is temporarily unavailable; do not retry."
    }

    private val runUrl = baseUrl.trimEnd('/') + "/run"

    // Before the service returns even a graceful "timed out" response it may wait
    // for a free worker (ACQUIRE_BUDGET) and then run for up to runTimeout. Our
    // HTTP timeout has to outlast that whole budget, plus some network slack, or
    // ktor would abort while the sandbox is still legitimately working. Derived
    // from runTimeout so raising SANDBOX_TIMEOUT_SECONDS keeps the two in sync.
    private val requestTimeout = ACQUIRE_BUDGET + runTimeout + NETWORK_SLACK

    suspend fun run(code: String, files: List<SandboxFile> = emptyList()): RunResponse {
        require(code.isNotBlank()) { "Code must not be blank" }

        return runCatching {
            http.post(runUrl) {
                contentType(ContentType.Application.Json)
                setBody(RunRequest(code, files))
                timeout {
                    // Both must use the derived budget: the sandbox sends no bytes
                    // while it computes, so the inherited 20s socket timeout would
                    // otherwise abort a long run before any response arrives.
                    requestTimeoutMillis = requestTimeout.inWholeMilliseconds
                    socketTimeoutMillis = requestTimeout.inWholeMilliseconds
                }
            }.body<RunResponse>()
        }.getOrElse { e ->
            e.rethrowIfCancellation()

            when (e) {
                is ConnectException, is UnresolvedAddressException -> throw IllegalStateException(UNREACHABLE_MESSAGE, e)
                else -> throw e
            }
        }
    }
}
