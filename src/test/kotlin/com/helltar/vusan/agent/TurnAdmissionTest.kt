package com.helltar.vusan.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TurnAdmissionTest {

    @Test
    fun `no more turns run at once than the ceiling allows`() = runBlocking {
        val admission = TurnAdmission(maxConcurrent = 2)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val turns =
            List(6) {
                async {
                    admission.admit {
                        peak.updateAndGet { seen -> maxOf(seen, running.incrementAndGet()) }
                        release.await()
                        running.decrementAndGet()
                        "done"
                    }
                }
            }

        while (running.get() < 2) yield()
        release.complete(Unit)

        assertEquals(List(6) { "done" }, turns.awaitAll())
        assertEquals(2, peak.get(), "more turns ran at once than the ceiling")
    }

    @Test
    fun `a turn is refused while there is no place and no room to wait`() = runBlocking {
        val admission = TurnAdmission(maxConcurrent = 1, maxWaiting = 0)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()

        val holder =
            launch {
                admission.admit {
                    started.complete(Unit)
                    release.await()
                }
            }

        started.await()

        assertNull(admission.admit { "refused" })

        release.complete(Unit)
        holder.join()

        // the place is free again, and a refused turn left nothing counted as waiting behind it
        assertEquals("after", admission.admit { "after" })
    }

    @Test
    fun `a turn with room to wait takes its place when one frees up`() = runBlocking {
        val admission = TurnAdmission(maxConcurrent = 1, maxWaiting = 1)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()

        val holder =
            launch {
                admission.admit {
                    started.complete(Unit)
                    release.await()
                }
            }

        started.await()

        val queued = async { admission.admit { "queued" } }
        val scheduled = async { admission.admitQueued { "fired" } }

        repeat(10) { yield() }

        assertFalse(queued.isCompleted, "a turn ran while the only place was taken")
        assertFalse(scheduled.isCompleted, "a queued turn ran while the only place was taken")

        release.complete(Unit)
        holder.join()

        assertEquals("queued", queued.await())
        assertEquals("fired", scheduled.await())
    }
}
