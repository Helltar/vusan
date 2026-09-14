package com.helltar.vusan.tools.workspace

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal const val WORKSPACE_FILE_LIMIT = 50 * 1024 * 1024

/**
 * The bot's side of a Regolith sandbox server: one named sandbox per person, holding their home.
 *
 * This is the only file that knows the `/v1` API. It carries its own client rather than Regolith's
 * Kotlin SDK, which is not published yet; [WorkspaceModels] holds the wire types it needs. A sandbox
 * is created on first use and returned unchanged after that, so no state is kept here beyond
 * remembering which ones this process has already asked for.
 */
class WorkspaceClient(
    http: HttpClient,
    baseUrl: String,
    private val token: String,
) {

    init {
        require(token.isNotBlank()) { "Workspace API authentication is required" }
    }

    // the configured API has no redirect contract; never relay its bearer secret to another origin.
    private val http = http.config { followRedirects = false }

    private val base = baseUrl.trimEnd('/')

    // sandboxes this process has created or found; a request that misses one clears it again.
    private val created = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var server: ServerInfo? = null

    suspend fun exec(workspaceId: String, command: String, timeoutSeconds: Int?): CommandResult {
        create(workspaceId)
        val started: ExecInfo = reachable {
            http.post("${sandbox(workspaceId)}/execs") {
                workspaceRequest()
                contentType(ContentType.Application.Json)
                setBody(ExecRequest(command, clamped(timeoutSeconds)))
            }.requireSuccess(workspaceId).body()
        }

        return collect(workspaceId, started.id, offset = 0, waitSeconds = FIRST_WAIT_SECONDS)
    }

    suspend fun readCommand(workspaceId: String, jobId: String, offset: Long, waitSeconds: Int): CommandResult =
        collect(workspaceId, jobId, offset, waitSeconds)

    suspend fun cancelCommand(workspaceId: String, jobId: String): CommandResult {
        val info: ExecInfo = reachable {
            http.post("${sandbox(workspaceId)}/execs/$jobId/cancel") {
                workspaceRequest()
            }.requireSuccess(workspaceId).body()
        }

        return info.result(output = "", nextOffset = 0, hasMore = false)
    }

    suspend fun listCommands(workspaceId: String): List<CommandResult> = reachable {
        http.get("${sandbox(workspaceId)}/execs") { workspaceRequest() }
            .requireSuccess(workspaceId).body<ExecPage>().execs
            .map { it.result(output = "", nextOffset = 0, hasMore = false) }
    }

    suspend fun writeFile(workspaceId: String, path: String, bytes: ByteArray) {
        require(bytes.size <= WORKSPACE_FILE_LIMIT) { "File exceeds the 50 MB transfer limit" }
        create(workspaceId)
        reachable {
            http.put("${sandbox(workspaceId)}/files/content") {
                workspaceRequest()
                parameter("path", path)
                setBody(bytes)
            }.requireSuccess(workspaceId)
        }
    }

    suspend fun deleteFile(workspaceId: String, path: String) {
        reachable {
            http.delete("${sandbox(workspaceId)}/files") {
                workspaceRequest()
                parameter("path", path)
                parameter("recursive", true)
            }.requireSuccess(workspaceId)
        }
    }

    /** Publishes a directory of the workspace to the web and returns the address it is served at. */
    suspend fun publishSite(workspaceId: String, path: String): PublishedSite {
        create(workspaceId)

        return reachable {
            http.post("${sandbox(workspaceId)}/publish") {
                workspaceRequest()
                contentType(ContentType.Application.Json)
                setBody(PublishRequest(path))
            }.requireSuccess(workspaceId).body()
        }
    }

    /** What is published for this person, or null when nothing is. */
    suspend fun publishedSite(workspaceId: String): PublishedSite? = reachable {
        val response = http.get("${sandbox(workspaceId)}/site") { workspaceRequest() }
        if (response.status == HttpStatusCode.NotFound) null else response.requireSuccess(workspaceId).body()
    }

    /** Takes the site down; false when there was nothing to take down. The workspace keeps its files. */
    suspend fun unpublishSite(workspaceId: String): Boolean = reachable {
        val response = http.delete("${sandbox(workspaceId)}/site") { workspaceRequest() }
        if (response.status == HttpStatusCode.NotFound) false else true.also { response.requireSuccess(workspaceId) }
    }

    /** Whether this server publishes at all; it says so in its own info rather than the bot guessing. */
    suspend fun publishes(): Boolean = info()?.publishing ?: false

    /** The names in one directory of the workspace, for a check before something is published. */
    suspend fun entries(workspaceId: String, path: String): List<String> = reachable {
        http.get("${sandbox(workspaceId)}/files/entries") {
            workspaceRequest()
            parameter("path", path)
        }.requireSuccess(workspaceId).body<DirectoryListing>().entries.map { it.name }
    }

    /** Deletes the sandbox with its home; the next use creates an empty one under the same name. */
    suspend fun resetWorkspace(workspaceId: String) {
        reachable {
            http.delete(sandbox(workspaceId)) { workspaceRequest() }.requireSuccess(workspaceId)
        }
        created -= workspaceId
    }

    suspend fun readFile(workspaceId: String, path: String, maxBytes: Int = WORKSPACE_FILE_LIMIT): ByteArray = reachable {
        require(maxBytes in 1..WORKSPACE_FILE_LIMIT) { "Invalid file transfer budget" }
        http.prepareGet("${sandbox(workspaceId)}/files/content") {
            workspaceRequest()
            parameter("path", path)
        }.execute { response ->
            response.requireSuccess(workspaceId)
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

    /**
     * Reads recorded output from [offset] until the command ends, this call's budget runs out, or the
     * page limit is reached, then reports the command as it now stands. Output is kept on the server,
     * so what does not fit is read by the next call from [CommandResult.nextOffset].
     */
    private suspend fun collect(workspaceId: String, jobId: String, offset: Long, waitSeconds: Int): CommandResult {
        val started = TimeSource.Monotonic.markNow()
        val output = StringBuilder()
        var next = offset
        var complete: Boolean
        var dropped = false

        while (true) {
            val remaining = (waitSeconds.seconds - started.elapsedNow()).inWholeSeconds.toInt().coerceAtLeast(0)
            val page = outputPage(workspaceId, jobId, next, remaining)
            page.frames.forEach { frame ->
                if (frame.kind == GAP_FRAME) dropped = true else output.append(frame.text)
            }
            next = page.nextOffset
            complete = page.complete
            // the server holds the request until there is output or the wait runs out, so an empty page
            // means nothing more arrived in time: looping again would only spin against it.
            val arrived = page.frames.isNotEmpty()
            val room = remaining > 0 && output.length < OUTPUT_CHARS
            if (complete || !arrived || !room) break
        }

        val info: ExecInfo = reachable {
            http.get("${sandbox(workspaceId)}/execs/$jobId") { workspaceRequest() }
                .requireSuccess(workspaceId).body()
        }

        return info.result(output.toString(), next, hasMore = !complete, dropped = dropped)
    }

    private suspend fun outputPage(workspaceId: String, jobId: String, offset: Long, waitSeconds: Int): OutputPage = reachable {
        http.get("${sandbox(workspaceId)}/execs/$jobId/output") {
            workspaceRequest()
            parameter("offset", offset)
            parameter("waitSeconds", waitSeconds)
            parameter("maxBytes", OUTPUT_CHARS)
        }.requireSuccess(workspaceId).body()
    }

    /** Creates the person's sandbox, or leaves the existing one exactly as it is. */
    private suspend fun create(workspaceId: String) {
        if (!created.add(workspaceId)) return

        try {
            reachable { http.put(sandbox(workspaceId)) { workspaceRequest() }.requireSuccess(workspaceId) }
        } catch (e: Throwable) {
            created -= workspaceId
            throw e
        }
    }

    /** The server owns its limits; a timeout above the ceiling would be refused instead of trimmed. */
    private suspend fun clamped(timeoutSeconds: Int?): Int? {
        if (timeoutSeconds == null) return null
        val ceiling = info()?.limits?.maxExecTimeoutSeconds ?: 0

        return if (ceiling > 0) minOf(timeoutSeconds, ceiling) else timeoutSeconds
    }

    /** What the server says about itself, read once: its limits and whether it can publish. */
    private suspend fun info(): ServerInfo? {
        server?.let { return it }

        return runCatching {
            reachable { http.get("$base/v1/info") { workspaceRequest() }.requireSuccess(null).body<ServerInfo>() }
        }.onFailure { it.rethrowIfCancellation() }.getOrNull()?.also { server = it }
    }

    private fun sandbox(workspaceId: String) = "$base/v1/sandboxes/$workspaceId"

    private fun HttpRequestBuilder.workspaceRequest() {
        bearerAuth(token)
        accept(ContentType.Application.Json)
        expectSuccess = false
        timeout {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private suspend fun HttpResponse.requireSuccess(workspaceId: String?): HttpResponse {
        if (status.isSuccess()) return this
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val problem = runCatching { problems.decodeFromString(ProblemDetails.serializer(), body) }.getOrNull()
        // the sandbox may have been deleted by retention or by hand: forget it, so the next call recreates it.
        if (problem?.code == NOT_FOUND_CODE && workspaceId != null) created -= workspaceId
        error(problem?.explain() ?: "The workspace API answered ${status.value}")
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

    private companion object {
        const val REQUEST_TIMEOUT_MS = 90_000L
        const val CHUNK_BYTES = 64 * 1024
        const val OUTPUT_CHARS = 16 * 1024
        const val FIRST_WAIT_SECONDS = 10
        const val GAP_FRAME = "gap"
        const val NOT_FOUND_CODE = "not_found"

        val problems = Json { ignoreUnknownKeys = true }
    }
}

private fun ProblemDetails.explain(): String = when (code) {
    "capacity_exhausted", "unavailable" ->
        "The workspace host is at capacity right now. Tell the user and try again in a minute."
    "busy" -> "This workspace already runs as many commands as it may; wait for one to finish."
    else -> detail.ifBlank { title }.ifBlank { "The workspace refused the request" }
}

private fun ExecInfo.result(output: String, nextOffset: Long, hasMore: Boolean, dropped: Boolean = false): CommandResult {
    val reason = outcome?.reason
    val status = commandStatus()

    return CommandResult(
        jobId = id,
        status = status,
        exitCode = outcome?.exitCode,
        output = output,
        nextOffset = nextOffset,
        hasMore = hasMore,
        truncated = dropped || outputTruncated,
        elapsedMs = elapsedMs(),
        limit = when (reason) {
            "oom_killed" -> CommandLimit.OUT_OF_MEMORY
            "pids_limited" -> CommandLimit.TOO_MANY_PROCESSES
            else -> null
        },
        reason = reason.takeIf { status == CommandStatus.INTERRUPTED },
    )
}

// an outcome type this client does not know is reported as interrupted: the model is then told to run
// the command again, which is right for anything that ended without the command deciding to.
private fun ExecInfo.commandStatus(): CommandStatus = when {
    status == "running" -> CommandStatus.RUNNING
    outcome?.type == "exited" -> CommandStatus.COMPLETED
    outcome?.type == "timed_out" -> CommandStatus.TIMED_OUT
    outcome?.type == "cancelled" -> CommandStatus.CANCELLED
    else -> CommandStatus.INTERRUPTED
}

private fun ExecInfo.elapsedMs(): Long {
    val start = startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return 0
    val end = finishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()

    return (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(0)
}
