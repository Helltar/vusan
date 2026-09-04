package com.helltar.vusan.tools.workspace

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class WorkspaceClientTest {

    private fun clientThatThrows(error: Throwable): WorkspaceClient {
        val http = Http.createClient(MockEngine { throw error })
        return WorkspaceClient(http, "http://vusan-workspace:8080", 600.seconds)
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
