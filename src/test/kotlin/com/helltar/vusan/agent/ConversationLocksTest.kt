package com.helltar.vusan.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationLocksTest {

    @Test
    fun `turns of one conversation run one after another, in the order they arrived`() = runBlocking {
        val locks = ConversationLocks<String>(maxWaiting = 3)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        var running = 0
        var peak = 0

        val turns =
            List(4) { index ->
                async {
                    locks.withLockIfRoom("alice") {
                        peak = maxOf(peak, ++running)
                        if (index == 0) release.await()
                        order += index
                        running--
                        index
                    }
                }.also { repeat(10) { yield() } }
            }

        release.complete(Unit)

        assertEquals(listOf(0, 1, 2, 3), turns.awaitAll())
        assertEquals(listOf(0, 1, 2, 3), order)
        assertEquals(1, peak, "two turns of one conversation ran side by side")
    }

    @Test
    fun `a turn is refused once the line is full, and taken again when it moves`() = runBlocking {
        val locks = ConversationLocks<String>(maxWaiting = 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holder = async { locks.withLockIfRoom("alice") { started.complete(Unit); release.await(); "first" } }
        started.await()

        val waiter = async { locks.withLockIfRoom("alice") { "second" } }
        repeat(10) { yield() }

        assertNull(locks.withLockIfRoom("alice") { "third" }, "the line had one place and it was taken")

        release.complete(Unit)
        assertEquals("first", holder.await())
        assertEquals("second", waiter.await())
        assertEquals("fourth", locks.withLockIfRoom("alice") { "fourth" })
    }

    @Test
    fun `with no line at all a second turn is refused at once`() = runBlocking {
        val locks = ConversationLocks<String>(maxWaiting = 0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holder = launch { locks.withLockIfRoom("alice") { started.complete(Unit); release.await() } }
        started.await()

        assertNull(locks.withLockIfRoom("alice") { "second" })
        assertEquals("bob", locks.withLockIfRoom("bob") { "bob" }, "another conversation has its own line")

        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `an unbounded caller waits behind a full line instead of being refused`() = runBlocking {
        val locks = ConversationLocks<String>(maxWaiting = 0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val holder = launch { locks.withLockIfRoom("alice") { started.complete(Unit); release.await() } }
        started.await()

        val scheduled = async { locks.withLock("alice") { "scheduled" } }
        repeat(10) { yield() }

        release.complete(Unit)
        holder.join()
        assertEquals("scheduled", scheduled.await())
    }
}
