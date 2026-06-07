package com.helltar.vusan.tools.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolGuardTest {

    @Test
    fun `returns failure string for regular exceptions`() = runBlocking {
        val result = suspendToolGuard { error("boom") }

        assertEquals("Tool failed: boom", result)
    }

    @Test
    fun `returns failure string for validation failures`() = runBlocking {
        val result = suspendToolGuard { require(false) { "bad emoji" }; "" }

        assertEquals("Tool failed: bad emoji", result)
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
