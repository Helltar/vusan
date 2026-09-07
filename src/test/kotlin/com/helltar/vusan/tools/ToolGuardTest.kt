package com.helltar.vusan.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolGuardTest {

    @Test
    fun `reports a regular exception as a failed tool call`() = runBlocking {
        assertEquals("Tool failed: boom", toolFailure { suspendToolGuard { error("boom") } })
    }

    @Test
    fun `reports a rejected argument as a failed tool call`() = runBlocking {
        assertEquals(
            "Tool failed: bad emoji",
            toolFailure { suspendToolGuard { require(false) { "bad emoji" }; "" } }
        )
    }

    @Test
    fun `rethrows cancellation`() {
        assertFailsWith<CancellationException> {
            runBlocking {
                suspendToolGuard { throw CancellationException("stop") }
            }
        }
    }
}
