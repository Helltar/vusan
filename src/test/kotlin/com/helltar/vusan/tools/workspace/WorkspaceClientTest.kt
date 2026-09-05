package com.helltar.vusan.tools.workspace

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class WorkspaceClientTest {

    @Test
    fun `API redirects are refused without forwarding the bearer secret`() = runBlocking {
        var requests = 0
        Http.createClient(MockEngine { request ->
            requests++
            assertEquals("workspace", request.url.host)
            respond(
                """{"error":"Unexpected API redirect"}""", HttpStatusCode.Found,
                headersOf(
                    HttpHeaders.Location to listOf("http://another-service/jobs"),
                    HttpHeaders.ContentType to listOf("application/json")
                )
            )
        }).use { http ->
            val client = WorkspaceClient(http, "http://workspace", 600.seconds, "test-token")
            assertFailsWith<IllegalStateException> { client.listCommands("u42") }
        }
        assertEquals(1, requests)
    }

    @Test
    fun `file size is bounded even without content length`() = runBlocking {
        val http = Http.createClient(MockEngine { respond(byteArrayOf(1, 2, 3, 4, 5), HttpStatusCode.OK) })
        val client = WorkspaceClient(http, "http://workspace", 600.seconds, "test-token")
        val error = assertFailsWith<IllegalStateException> { client.readFile("u1", "sample.bin", 4) }
        assertContains(error.message.orEmpty(), "transfer limit")
    }

    @Test
    fun `declared oversize is refused before accepting the response body`() = runBlocking {
        val http = Http.createClient(MockEngine {
            respond(byteArrayOf(1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "100"))
        })
        val client = WorkspaceClient(http, "http://workspace", 600.seconds, "test-token")
        val error = assertFailsWith<IllegalArgumentException> { client.readFile("u1", "sample.bin", 4) }
        assertContains(error.message.orEmpty(), "transfer limit")
    }

    @Test
    fun `poll requests keep workspace scope and byte offset`() = runBlocking {
        val http = Http.createClient(MockEngine { request ->
            assertEquals("u42", request.url.parameters["id"])
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertEquals("16384", request.url.parameters["offset"])
            assertEquals("20", request.url.parameters["waitSeconds"])
            respond("""{"jobId":"d6e07bfb-61dd-469a-94ad-2d05e1a19493","status":"completed"}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = WorkspaceClient(http, "http://workspace", 600.seconds, "test-token")
            .readCommand("u42", "d6e07bfb-61dd-469a-94ad-2d05e1a19493", 16384, 20)
        assertEquals(CommandStatus.COMPLETED, result.status)
    }

    private fun clientThatThrows(error: Throwable): WorkspaceClient {
        val http = Http.createClient(MockEngine { throw error })
        return WorkspaceClient(http, "http://vusan-workspace:8080", 600.seconds, "test-token")
    }

    @Test
    fun `unresolved host maps to a clear unreachable message`() = runBlocking {
        val client = clientThatThrows(UnresolvedAddressException())
        val e = assertFailsWith<IllegalStateException> { client.exec("u1", "ls", null) }
        assertContains(e.message ?: "", "temporarily unavailable")
    }

    @Test
    fun `connection refused maps to a clear unreachable message`() = runBlocking {
        val client = clientThatThrows(ConnectException("Connection refused"))
        val e = assertFailsWith<IllegalStateException> { client.exec("u1", "ls", null) }
        assertContains(e.message ?: "", "temporarily unavailable")
    }

    @Test
    fun `unrelated failure is rethrown unchanged`() = runBlocking {
        val client = clientThatThrows(IllegalArgumentException("engine gave up"))
        val e = assertFailsWith<IllegalArgumentException> { client.exec("u1", "ls", null) }
        assertContains(e.message ?: "", "engine gave up")
    }
}
