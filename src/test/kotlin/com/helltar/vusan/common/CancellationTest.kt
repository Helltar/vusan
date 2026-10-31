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
 * Rethrowing what must not be swallowed, and running a block whose cancellation is someone's decision rather
 * than the caller's end. [runInOwnJob] fits the scheduler's shape: a loop with more to do, holding a turn
 * something outside may stop by naming its conversation; [RunningTurns], which `/stop` reaches, can only
 * cancel the turn's coroutine.
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
