package com.helltar.vusan.infra

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Liveness signal for the container healthcheck.
 *
 * The heartbeat file's modification time is the only thing the healthcheck reads, and it is
 * refreshed only while the long polling loop keeps cycling. Nothing outside the process can see
 * that on its own: polling runs on a scheduled executor, so the loop can stop while the JVM stays
 * up and the container stays `Up` forever.
 *
 * Freshness follows the poll loop rather than whether Telegram answers, so a brief outage upstream
 * does not make every bot unhealthy at once over something restarting them cannot fix. A long one
 * still does, and that is the useful part: the session's backoff decays to a 15-minute retry
 * interval, and at that point the bot is polling in name only and a restart is what clears it.
 */
internal class Heartbeat(
    private val file: Path = Path.of(HEARTBEAT_FILE),
    private val staleAfter: Duration = STALE_AFTER,
    private val interval: Duration = WRITE_INTERVAL
) {

    private companion object {
        const val HEARTBEAT_FILE = "/tmp/health"

        val WRITE_INTERVAL = 30.seconds

        // getUpdates long-polls for 50s and okhttp gives it a 100s read timeout, so even a request
        // that hangs until it times out still marks a cycle inside this window. what falls outside
        // it is a loop that stopped, or one backing off so hard it polls in name only.
        val STALE_AFTER = 180.seconds

        val log = KotlinLogging.logger {}
    }

    // set from the polling thread, read from the heartbeat coroutine
    private val lastPollAt = AtomicLong(0)

    // only the heartbeat coroutine touches these, so each transition is logged once
    private var stallReported = false
    private var writeFailureReported = false

    /** Records that the long polling loop is about to issue another `getUpdates` request. */
    fun markPoll() = lastPollAt.set(System.currentTimeMillis())

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                refresh()
                delay(interval)
            }
        }

    private fun refresh() {
        val since = lastPollAt.get()

        // zero until the first cycle: a bot whose polling never started must not pass the
        // healthcheck merely because its process is up
        if (since == 0L) return

        if (System.currentTimeMillis() - since >= staleAfter.inWholeMilliseconds) {
            reportStall()
            return
        }

        if (stallReported) {
            stallReported = false
            log.info { "getUpdates polling resumed" }
        }

        runCatching { Files.writeString(file, Instant.now().toString()) }
            .onSuccess { writeFailureReported = false }
            .onFailure { reportWriteFailure(it) }
    }

    private fun reportStall() {
        if (stallReported) return

        stallReported = true

        log.error {
            "No getUpdates cycle in ${staleAfter.inWholeSeconds}s — the bot is no longer polling Telegram. " +
                    "The heartbeat file is now stale, so the container healthcheck will fail."
        }
    }

    private fun reportWriteFailure(cause: Throwable) {
        if (writeFailureReported) return

        writeFailureReported = true

        log.warn(cause) { "Failed to write the heartbeat file=[$file] — the healthcheck cannot see this bot" }
    }
}
