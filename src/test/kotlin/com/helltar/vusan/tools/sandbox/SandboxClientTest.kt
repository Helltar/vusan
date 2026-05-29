package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class SandboxClientTest {

    private fun clientThatThrows(error: Throwable): SandboxClient {
        val http = Http.createClient(MockEngine { throw error })
        return SandboxClient(http, "http://sandbox:8080", 30.seconds)
    }

    @Test
    fun `unresolved host maps to a clear unreachable message`() = runBlocking {
        val client = clientThatThrows(UnresolvedAddressException())
        val e = assertFailsWith<IllegalStateException> { client.run("print(1)") }
        assertContains(e.message ?: "", "temporarily unavailable")
    }

    @Test
    fun `connection refused maps to a clear unreachable message`() = runBlocking {
        val client = clientThatThrows(ConnectException("Connection refused"))
        val e = assertFailsWith<IllegalStateException> { client.run("print(1)") }
        assertContains(e.message ?: "", "temporarily unavailable")
    }

    @Test
    fun `unrelated failure is rethrown unchanged`() = runBlocking {
        val cause = IllegalArgumentException("boom")
        val client = clientThatThrows(cause)
        val e = assertFailsWith<IllegalArgumentException> { client.run("print(1)") }
        assertContains(e.message ?: "", "boom")
    }
}
