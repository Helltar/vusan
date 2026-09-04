package com.helltar.vusan.tools.workspace

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

class WorkspaceClient(
    private val http: HttpClient,
    baseUrl: String,
    private val maxCommandTimeout: Duration,
    private val token: String? = null
) {

    private companion object {
        val NETWORK_SLACK = 20.seconds

        // returned when the service cannot be reached at all — a container that is not running or a
        // wrong URL. framed so the model tells the user instead of rewriting the command and retrying.
        const val UNREACHABLE_MESSAGE =
            "The workspace is not reachable right now, so nothing ran. " +
                    "Tell the user the workspace is temporarily unavailable; do not retry."
    }

    private val base = baseUrl.trimEnd('/')

    // the service holds the connection open for the whole command without sending a byte, so the
    // inherited socket timeout would abort a long build that is still legitimately running
    private val requestTimeout = maxCommandTimeout + NETWORK_SLACK

    suspend fun exec(workspaceId: String, command: String, timeoutSeconds: Int?): ExecResponse =
        reachable {
            http.post("$base/exec") {
                auth()
                contentType(ContentType.Application.Json)
                setBody(ExecRequest(workspaceId, command, timeoutSeconds))
                timeout {
                    requestTimeoutMillis = requestTimeout.inWholeMilliseconds
                    socketTimeoutMillis = requestTimeout.inWholeMilliseconds
                }
            }.body()
        }

    suspend fun writeFile(workspaceId: String, path: String, bytes: ByteArray) {
        reachable {
            http.put("$base/files") {
                auth()
                parameter("id", workspaceId)
                parameter("path", path)
                setBody(bytes)
            }
        }
    }

    suspend fun readFile(workspaceId: String, path: String): ByteArray =
        reachable {
            http.get("$base/files") {
                auth()
                parameter("id", workspaceId)
                parameter("path", path)
            }.body()
        }

    suspend fun list(workspaceId: String, path: String): ListResponse =
        reachable {
            http.get("$base/list") {
                auth()
                parameter("id", workspaceId)
                parameter("path", path)
            }.body()
        }

    private fun HttpRequestBuilder.auth() {
        token?.let { bearerAuth(it) }
    }

    private suspend fun <T> reachable(block: suspend () -> T): T =
        runCatching { block() }.getOrElse { e ->
            e.rethrowIfCancellation()

            when (e) {
                is ConnectException, is UnresolvedAddressException -> throw IllegalStateException(UNREACHABLE_MESSAGE, e)
                else -> throw e
            }
        }
}
