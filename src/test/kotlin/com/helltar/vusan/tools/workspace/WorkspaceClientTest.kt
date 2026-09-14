package com.helltar.vusan.tools.workspace

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private const val JOB = "9b7f0d2c4e6a418d93f5c0b1a2d3e4f5"

private const val FINISHED =
    """{"id":"$JOB","status":"finished","outcome":{"type":"exited","exitCode":0},""" +
        """"startedAt":"2026-09-13T12:00:00Z","finishedAt":"2026-09-13T12:00:01Z","outputEnd":6,"outputTruncated":false,"stdinOpen":false}"""

private fun MockRequestHandleScope.json(body: String) =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

class WorkspaceClientTest {

    @Test
    fun `API redirects are refused without forwarding the bearer secret`() = runBlocking {
        var requests = 0
        Http.createClient(MockEngine { request ->
            requests++
            assertEquals("workspace", request.url.host)
            respond(
                """{"code":"not_found","detail":"Unexpected API redirect"}""", HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location to listOf("http://another-service/v1/sandboxes/u42/execs"),
                    HttpHeaders.ContentType to listOf("application/problem+json"),
                ),
            )
        }).use { http ->
            val client = WorkspaceClient(http, "http://workspace", "test-token")
            assertFailsWith<IllegalStateException> { client.listCommands("u42") }
        }
        assertEquals(1, requests)
    }

    @Test
    fun `file size is bounded even without content length`() = runBlocking {
        val http = Http.createClient(MockEngine { respond(byteArrayOf(1, 2, 3, 4, 5), HttpStatusCode.OK) })
        val client = WorkspaceClient(http, "http://workspace", "test-token")
        val error = assertFailsWith<IllegalStateException> { client.readFile("u1", "sample.bin", 4) }
        assertContains(error.message.orEmpty(), "transfer limit")
    }

    @Test
    fun `declared oversize is refused before accepting the response body`() = runBlocking {
        val http = Http.createClient(MockEngine {
            respond(byteArrayOf(1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "100"))
        })
        val client = WorkspaceClient(http, "http://workspace", "test-token")
        val error = assertFailsWith<IllegalArgumentException> { client.readFile("u1", "sample.bin", 4) }
        assertContains(error.message.orEmpty(), "transfer limit")
    }

    @Test
    fun `a command is started in the person's own sandbox, once created`() = runBlocking {
        val paths = mutableListOf<String>()
        val http = Http.createClient(MockEngine { request ->
            paths += "${request.method.value} ${request.url.encodedPath}"
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            when {
                request.url.encodedPath.endsWith("/output") -> json("""{"frames":[{"kind":"stdout","text":"hello","end":6}],"nextOffset":6,"complete":true}""")
                request.url.encodedPath.endsWith("/execs") && request.method == HttpMethod.Post -> json(FINISHED)
                request.url.encodedPath.endsWith("/u42") -> json("""{"name":"u42"}""")
                else -> json(FINISHED)
            }
        })
        val client = WorkspaceClient(http, "http://workspace", "test-token")

        val first = client.exec("u42", "echo hello", 30)
        client.exec("u42", "echo hello", 30)

        assertEquals("hello", first.output)
        assertEquals(CommandStatus.COMPLETED, first.status)
        assertEquals(0, first.exitCode)
        // the sandbox is created once and then reused; the commands do not ask for it again.
        assertEquals(1, paths.count { it == "PUT /v1/sandboxes/u42" })
        assertTrue("POST /v1/sandboxes/u42/execs" in paths)
    }

    @Test
    fun `polling keeps the byte offset and stops when nothing more arrives`() = runBlocking {
        val offsets = mutableListOf<String>()
        val http = Http.createClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/output")) {
                offsets += request.url.parameters["offset"].orEmpty()
                json("""{"frames":[],"nextOffset":16384,"complete":false}""")
            } else {
                json("""{"id":"$JOB","status":"running","startedAt":"2026-09-13T12:00:00Z","outputEnd":16384,"outputTruncated":false,"stdinOpen":false}""")
            }
        })
        val client = WorkspaceClient(http, "http://workspace", "test-token")

        val result = client.readCommand("u42", JOB, offset = 16384, waitSeconds = 20)

        assertEquals(listOf("16384"), offsets)
        assertEquals(CommandStatus.RUNNING, result.status)
        assertTrue(result.hasMore)
    }

    @Test
    fun `a problem document explains a refusal in the words the model reads`() = runBlocking {
        val http = Http.createClient(MockEngine {
            respond(
                """{"type":"urn:regolith:error:capacity_exhausted","title":"No session capacity","status":503,"detail":"Every session slot is busy","code":"capacity_exhausted"}""",
                HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        })
        val client = WorkspaceClient(http, "http://workspace", "test-token")
        val error = assertFailsWith<IllegalStateException> { client.exec("u42", "ls", null) }
        assertContains(error.message.orEmpty(), "at capacity")
    }

    @Test
    fun `an unreachable service is reported as temporary, not as a bug`() = runBlocking {
        for (failure in listOf(ConnectException("refused"), UnresolvedAddressException())) {
            val http = Http.createClient(MockEngine { throw failure })
            val client = WorkspaceClient(http, "http://workspace", "test-token")
            val error = assertFailsWith<IllegalStateException> { client.listCommands("u42") }
            assertContains(error.message.orEmpty(), "temporarily unavailable")
        }
    }
}
