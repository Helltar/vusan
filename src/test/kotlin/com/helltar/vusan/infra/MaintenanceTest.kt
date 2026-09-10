package com.helltar.vusan.infra

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

class MaintenanceTest {

    @Test
    fun `a step that fails leaves the ones after it to run`() = runBlocking {
        val done = mutableListOf<String>()
        val finished = CompletableDeferred<Unit>()

        val maintenance =
            Maintenance(
                listOf(
                    Maintenance.Step("first") {
                        done += "first"
                        1
                    },
                    Maintenance.Step("broken") { error("the table is locked") },
                    Maintenance.Step("last") {
                        done += "last"
                        finished.complete(Unit)
                        0
                    }
                ),
                interval = 1.hours
            )

        val job = maintenance.launchIn(this)
        finished.await()
        job.cancelAndJoin()

        assertEquals(listOf("first", "last"), done)
    }

    // the pass runs on the way in, which is what a bot that is only ever up briefly relies on.
    @Test
    fun `the first pass does not wait for the interval`() = runBlocking {
        val ran = CompletableDeferred<Unit>()

        val maintenance = Maintenance(listOf(Maintenance.Step("once") { ran.complete(Unit); 0 }), interval = 1.hours)
        val job = maintenance.launchIn(this)

        ran.await()
        job.cancelAndJoin()
    }
}
