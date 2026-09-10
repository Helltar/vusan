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
 * Everything here is also pruned where it is written — a turn prunes its own conversation, a chat its
 * own transcript — and that is enough for as long as somebody keeps writing. It is people who stop who
 * need this: a conversation nobody comes back to, a chat the bot was left in, a poll never followed by
 * another one. None of them will produce the write that would have cleaned them up.
 *
 * Each [Step] bounds its own pass and answers how much it removed, so a long-neglected database is
 * caught up over several rounds rather than in one transaction holding the write lock.
 */
class Maintenance(
    private val steps: List<Step>,
    private val interval: Duration = DEFAULT_INTERVAL
) {

    /** One thing to clean up, and what it removed: rows, chats, conversations — whatever it counts. */
    class Step(val name: String, val run: suspend () -> Int)

    private companion object {
        val log = KotlinLogging.logger {}

        // retention is measured in days everywhere it is configured, so nothing here is urgent; a
        // restart runs a pass on the way in, which is what catches a bot that is only up briefly.
        val DEFAULT_INTERVAL = 6.hours
    }

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
}
