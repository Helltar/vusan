package com.helltar.vusan.tools.workspace

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration

internal const val WORKSPACE_FILE_LIMIT = 50 * 1024 * 1024

class WorkspaceClient(
    http: HttpClient,
    baseUrl: String,
    private val maxCommandTimeout: Duration,
    private val token: String
) {
    init {
        require(token.isNotBlank()) { "Workspace API authentication is required" }
    }

    // the configured API has no redirect contract; never relay its bearer secret to another origin.
    private val http = http.config { followRedirects = false }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 90_000L
        const val CHUNK_BYTES = 64 * 1024
    }

    private val base = baseUrl.trimEnd('/')

    suspend fun exec(workspaceId: String, command: String, timeoutSeconds: Int?): CommandResult = reachable {
        http.post("$base/jobs") {
            workspaceRequest(workspaceId)
            contentType(ContentType.Application.Json)
            setBody(CommandRequest(command, timeoutSeconds?.coerceAtMost(maxCommandTimeout.inWholeSeconds.toInt())))
        }.requireSuccess().body()
    }

    suspend fun readCommand(workspaceId: String, jobId: String, offset: Long, waitSeconds: Int): CommandResult = reachable {
        http.get("$base/jobs/$jobId") {
            workspaceRequest(workspaceId)
            parameter("offset", offset)
            parameter("waitSeconds", waitSeconds)
        }.requireSuccess().body()
    }

    suspend fun cancelCommand(workspaceId: String, jobId: String): CommandResult = reachable {
        http.delete("$base/jobs/$jobId") { workspaceRequest(workspaceId) }.requireSuccess().body()
    }

    suspend fun listCommands(workspaceId: String): List<CommandResult> = reachable {
        http.get("$base/jobs") { workspaceRequest(workspaceId) }.requireSuccess().body<CommandList>().jobs
    }

    suspend fun writeFile(workspaceId: String, path: String, bytes: ByteArray) {
        require(bytes.size <= WORKSPACE_FILE_LIMIT) { "File exceeds the 50 MB transfer limit" }
        reachable {
            http.put("$base/files") {
                workspaceRequest(workspaceId)
                parameter("path", path)
                setBody(bytes)
            }.requireSuccess()
        }
    }

    suspend fun readFile(workspaceId: String, path: String, maxBytes: Int = WORKSPACE_FILE_LIMIT): ByteArray = reachable {
        require(maxBytes in 1..WORKSPACE_FILE_LIMIT) { "Invalid file transfer budget" }
        http.prepareGet("$base/files") {
            workspaceRequest(workspaceId)
            parameter("path", path)
        }.execute { response ->
            response.requireSuccess()
            val declared = response.contentLength()
            require(declared == null || declared <= maxBytes) { "File exceeds the remaining transfer limit" }
            val channel = response.bodyAsChannel()
            val output = ByteArrayOutputStream(CHUNK_BYTES)
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = channel.readAvailable(chunk)
                if (read < 0) break
                if (output.size() + read > maxBytes) {
                    channel.cancel()
                    error("File exceeds the remaining transfer limit")
                }
                output.write(chunk, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun HttpRequestBuilder.workspaceRequest(workspaceId: String) {
        bearerAuth(token)
        parameter("id", workspaceId)
        expectSuccess = false
        timeout {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private suspend fun HttpResponse.requireSuccess(): HttpResponse {
        check(status.isSuccess()) { body<WorkspaceError>().error }
        return this
    }

    private suspend fun <T> reachable(block: suspend () -> T): T =
        runCatching { block() }.getOrElse { e ->
            e.rethrowIfCancellation()
            when (e) {
                is ConnectException, is UnresolvedAddressException ->
                    error("The workspace is temporarily unavailable. Tell the user; do not retry immediately.")
                else -> throw e
            }
        }
}
