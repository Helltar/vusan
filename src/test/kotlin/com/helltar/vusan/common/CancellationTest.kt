package com.helltar.vusan.common

import com.helltar.vusan.agent.RunningTurns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rethrowing what must not be swallowed, and running a block where being cancelled is somebody's
 * decision rather than the end of the caller.
 *
 * [runInOwnJob] is written for the shape the task scheduler fires in: a loop that has more to do, and
 * inside it a turn something outside may stop by naming its conversation. [RunningTurns] is what
 * `/stop` reaches, and it can only cancel the coroutine the turn is running in.
 */
class CancellationTest {

    @Test
    fun `rethrowIfCancellation rethrows cancellation exceptions`() {
        assertFailsWith<CancellationException> {
            CancellationException("stop").rethrowIfCancellation()
        }
    }

    @Test
    fun `rethrowIfCancellation ignores non cancellation throwables`() {
        IllegalStateException("boom").rethrowIfCancellation()
    }

    @Test
    fun `stopping the block leaves the caller running`() = runBlocking {
        val turns = RunningTurns<String>()
        val started = CompletableDeferred<Unit>()
        var outcome: String? = "delivered"
        var carriedOn = false

        val loop = launch {
            outcome =
                runInOwnJob {
                    turns.track("a chat") {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }

            carriedOn = true
        }

        started.await()
        assertTrue(turns.cancel("a chat"))
        loop.join()

        assertNull(outcome)
        assertTrue(carriedOn, "the scheduler's loop ended with the turn it fired")
    }

    @Test
    fun `the caller's own cancellation is not a stopped block`() = runBlocking {
        val turns = RunningTurns<String>()
        val started = CompletableDeferred<Unit>()
        var carriedOn = false

        val loop = launch {
            runInOwnJob {
                turns.track("a chat") {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }

            carriedOn = true
        }

        started.await()
        loop.cancelAndJoin()

        assertFalse(carriedOn, "a shutdown was answered as if a single block had been stopped")
    }

    @Test
    fun `a block that finishes hands its answer back`() = runBlocking {
        assertEquals("delivered", runInOwnJob { "delivered" })
    }
}
