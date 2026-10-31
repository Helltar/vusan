package com.helltar.vusan.infra

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The pass that keeps stored data from outliving its retention.
 *
 * Everything is also pruned where it is written (a turn prunes its conversation, a chat its transcript),
 * which suffices while people keep writing. This is for those who stop: a conversation nobody returns to, a
 * chat the bot was left in, a poll never followed by another. None will produce the write that would clean it
 * up.
 *
 * Each [Step] bounds its own pass and returns how much it removed, so a long-neglected database catches up
 * over several rounds instead of one transaction holding the write lock.
 */
class Maintenance(
    private val steps: List<Step>,
    private val interval: Duration = DEFAULT_INTERVAL,
) {

    /** One thing to clean up, and how much it removed, in whatever unit it counts. */
    class Step(val name: String, val run: suspend () -> Int)

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            log.info { "maintenance started: every ${interval.inWholeHours}h, ${steps.size} step(s)" }

            while (true) {
                runPass()
                delay(interval)
            }
        }

    private suspend fun runPass() {
        steps.forEach { step ->
            runCatching { step.run() }
                .onSuccess { removed -> if (removed > 0) log.info { "maintenance ${step.name}: removed $removed" } }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.warn(it) { "maintenance ${step.name} failed; the next pass tries again" }
                }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}

        // retention is in days everywhere, so nothing is urgent; a restart runs a pass on the way in, which
        // covers a bot that is only up briefly.
        val DEFAULT_INTERVAL = 6.hours
    }
}
