package com.helltar.vusan.infra

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class HeartbeatTest {

    @Test
    fun `heartbeat file is absent until the first poll cycle`() {
        withHeartbeat { file, _ ->
            delay(SETTLE)

            assertFalse(Files.exists(file), "a bot whose polling never started must not pass the healthcheck")
        }
    }

    @Test
    fun `heartbeat file is refreshed while poll cycles keep coming`() {
        withHeartbeat { file, heartbeat ->
            heartbeat.markPoll()
            delay(SETTLE)

            assertTrue(Files.exists(file))
            val first = Files.readString(file)

            heartbeat.markPoll()
            delay(SETTLE)

            assertNotEquals(first, Files.readString(file), "a live polling loop must keep the file fresh")
        }
    }

    @Test
    fun `heartbeat file goes stale once poll cycles stop`() {
        withHeartbeat { file, heartbeat ->
            heartbeat.markPoll()
            delay(SETTLE)
            assertTrue(Files.exists(file))

            // no further markPoll: past the staleness window the file must stop being touched, which is
            // what turns a silently dead polling loop into a failing healthcheck
            delay(STALE_AFTER + SETTLE)
            val frozen = Files.readString(file)

            delay(SETTLE)

            assertEquals(frozen, Files.readString(file), "a stalled polling loop must stop refreshing the file")
        }
    }

    private fun withHeartbeat(block: suspend CoroutineScope.(Path, Heartbeat) -> Unit) {
        val dir = Files.createTempDirectory("vusan-heartbeat-test")
        val file = dir.resolve("health")

        try {
            runBlocking {
                val heartbeat = Heartbeat(file, staleAfter = STALE_AFTER, interval = INTERVAL)
                val job = heartbeat.launchIn(this)

                try {
                    block(file, heartbeat)
                } finally {
                    job.cancelAndJoin()
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        val INTERVAL = 20.milliseconds
        val STALE_AFTER = 200.milliseconds

        // several write attempts, so a single slow tick does not decide the assertion
        val SETTLE: Duration = 100.milliseconds
    }
}
