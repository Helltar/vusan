package com.helltar.vusan.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunningTurnsTest {

    @Test
    fun `a running turn is cancelled by its key, and the block unwinds`() = runBlocking {
        val turns = RunningTurns<String>()
        val started = CompletableDeferred<Unit>()
        var cleanedUp = false

        val turn =
            async {
                turns.track("alice") {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cleanedUp = true
                    }
                }
            }

        started.await()
        assertTrue(turns.cancel("alice"))
        assertTrue(runCatching { turn.await() }.isFailure)
        assertTrue(cleanedUp, "the turn must be given the chance to release what it holds")

        // and it is gone from the register, so a second stop has nothing to say
        assertFalse(turns.cancel("alice"))
    }

    @Test
    fun `stopping one conversation leaves the others running`() = runBlocking {
        val turns = RunningTurns<String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val bob =
            async {
                turns.track("bob") {
                    started.complete(Unit)
                    release.await()
                    "finished"
                }
            }

        started.await()
        assertFalse(turns.cancel("alice"), "nobody is running for alice")
        release.complete(Unit)
        assertEquals("finished", bob.await())
    }

    @Test
    fun `a finished turn never cancels the one that replaced it`() = runBlocking {
        val turns = RunningTurns<String>()

        turns.track("alice") { }

        val second = CompletableDeferred<Unit>()
        val running = async { turns.track("alice") { second.complete(Unit); awaitCancellation() } }

        second.await()
        assertTrue(turns.cancel("alice"))
        assertTrue(runCatching { running.await() }.isFailure)
    }
}
