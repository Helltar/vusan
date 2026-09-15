package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
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

private const val RUNNING =
    """{"id":"$JOB","status":"running","startedAt":"2026-09-13T12:00:00Z","outputEnd":16384,"outputTruncated":false,"stdinOpen":false}"""

private fun MockRequestHandleScope.json(body: String) =
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

class SandboxClientTest {

    @Test
    fun `API redirects are refused without forwarding the bearer secret`() = runBlocking {
        var requests = 0
        Http.createClient(MockEngine { request ->
            requests++
            assertEquals("sandbox", request.url.host)
            respond(
                """{"code":"not_found","detail":"Unexpected API redirect"}""", HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location to listOf("http://another-service/v1/sandboxes/$SANDBOX_ID/execs"),
                    HttpHeaders.ContentType to listOf("application/problem+json"),
                ),
            )
        }).use { http ->
            val client = SandboxClient(http, "http://sandbox", "test-token")
            assertFailsWith<IllegalStateException> { client.open("telegram:42").listCommands() }
        }
        assertEquals(1, requests)
    }

    @Test
    fun `a file past the remaining budget is asked for with that bound and refused`() = runBlocking {
        val bounds = mutableListOf<String?>()
        val http = Http.createClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/sandboxes") return@MockEngine json(sandboxInfo("telegram:1"))
            bounds += request.url.parameters["maxBytes"]
            respond(
                problemDocument("payload_too_large", 413, "Payload too large", "`/home/sandbox/sample.bin` is larger than 4 bytes"),
                HttpStatusCode.PayloadTooLarge,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        })
        val client = SandboxClient(http, "http://sandbox", "test-token")

        val error = assertFailsWith<IllegalStateException> { client.open("telegram:1").readFile("sample.bin", 4) }

        assertContains(error.message.orEmpty(), "transfer limit")
        assertEquals(listOf<String?>("4"), bounds)
    }

    @Test
    fun `a file is never held past the budget, whatever the server sends`() = runBlocking {
        val http = Http.createClient(MockEngine { request ->
            if (request.url.encodedPath == "/v1/sandboxes") json(sandboxInfo("telegram:1")) else respond(byteArrayOf(1, 2, 3, 4, 5), HttpStatusCode.OK)
        })
        val client = SandboxClient(http, "http://sandbox", "test-token")

        val error = assertFailsWith<IllegalStateException> { client.open("telegram:1").readFile("sample.bin", 4) }

        assertContains(error.message.orEmpty(), "transfer limit")
    }

    @Test
    fun `a full disk is told in the server's words, not as a file too large`() = runBlocking {
        val detail = "The disk that holds `/home/sandbox/notes.txt` is full; delete files to make room"
        val http = Http.createClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/files/content")) {
                respond(
                    problemDocument("insufficient_storage", 507, "Insufficient storage", detail),
                    HttpStatusCode.InsufficientStorage,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            } else {
                json(sandboxInfo("telegram:1"))
            }
        })
        val client = SandboxClient(http, "http://sandbox", "test-token")

        val error = assertFailsWith<IllegalStateException> { client.open("telegram:1").writeFile("notes.txt", byteArrayOf(1)) }

        assertEquals(detail, error.message)
    }

    @Test
    fun `a command is started in the person's own sandbox, opened by their alias`() = runBlocking {
        val paths = mutableListOf<String>()
        val http = Http.createClient(MockEngine { request ->
            paths += "${request.method.value} ${request.url.encodedPath}"
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            when {
                request.url.encodedPath.endsWith("/output") -> json("""{"frames":[{"kind":"stdout","text":"hello","end":6}],"nextOffset":6,"complete":true}""")
                request.url.encodedPath.endsWith("/execs") && request.method == HttpMethod.Post -> json(FINISHED)
                request.url.encodedPath == "/v1/sandboxes" -> json(sandboxInfo("telegram:42"))
                else -> json(FINISHED)
            }
        })
        val client = SandboxClient(http, "http://sandbox", "test-token")

        val first = client.open("telegram:42").exec("echo hello", 30)
        client.open("telegram:42").exec("echo hello", 30)


        assertEquals("hello", first.output)
        assertEquals(CommandStatus.COMPLETED, first.status)
        assertEquals(0, first.exitCode)
        // opening asks the server for the person's sandbox; the id it answers with addresses the rest.
        assertEquals("POST /v1/sandboxes", paths.first())
        assertEquals(2, paths.count { it == "POST /v1/sandboxes" })
        assertTrue("POST /v1/sandboxes/$SANDBOX_ID/execs" in paths)
    }

    @Test
    fun `polling keeps the byte offset and stops when nothing more arrives`() = runBlocking {
        val offsets = mutableListOf<String>()
        val http = Http.createClient(MockEngine { request ->
            when {
                request.url.encodedPath == "/v1/sandboxes" -> json(sandboxInfo("telegram:42"))
                request.url.encodedPath.endsWith("/output") -> {
                    offsets += request.url.parameters["offset"].orEmpty()
                    json("""{"frames":[],"nextOffset":16384,"complete":false}""")
                }

                else -> json(RUNNING)
            }
        })
        val client = SandboxClient(http, "http://sandbox", "test-token")

        val result = client.open("telegram:42").readCommand(JOB, offset = 16384, waitSeconds = 20)

        assertEquals(listOf("16384"), offsets)
        assertEquals(CommandStatus.RUNNING, result.status)
        assertTrue(result.hasMore)
    }

    @Test
    fun `a problem document explains a refusal in the words the model reads`() = runBlocking {
        val http = Http.createClient(MockEngine {
            respond(
                problemDocument("capacity_exhausted", 503, "No session capacity", "Every session slot is busy"),
                HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        })
        val client = SandboxClient(http, "http://sandbox", "test-token")
        val error = assertFailsWith<IllegalStateException> { client.open("telegram:42").exec("ls", null) }
        assertContains(error.message.orEmpty(), "at capacity")
    }

    @Test
    fun `a service that does not answer is reported as temporary, not as a bug`() = runBlocking {
        val failures = listOf(
            ConnectException("refused"),
            UnresolvedAddressException(),
            HttpRequestTimeoutException("http://sandbox/v1/sandboxes/$SANDBOX_ID/execs", 90_000),
        )

        for (failure in failures) {
            val http = Http.createClient(MockEngine { throw failure })
            val client = SandboxClient(http, "http://sandbox", "test-token")
            val error = assertFailsWith<IllegalStateException> { client.open("telegram:42").listCommands() }
            assertContains(error.message.orEmpty(), "temporarily unavailable")
        }
    }
}
