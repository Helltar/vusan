package com.helltar.vusan.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

/**
 * Runs [block] in a job of its own, and answers `null` when something outside cancelled that job.
 *
 * Whatever is cancelled by name from outside — the agent's running turns, which `/stop` ends — cancels
 * the coroutine the call happens to sit in, having nothing else to cancel. A caller that goes on to do
 * more (the task scheduler, whose loop would end there) hands the block a job to be cancelled instead.
 * The caller's own cancellation still wins: a service shutting down is not a stopped turn.
 */
internal suspend fun <T> runInOwnJob(block: suspend () -> T): T? =
    try {
        coroutineScope { async { block() }.await() }
    } catch (stopped: CancellationException) {
        // when the caller is cancelled too this is the shutdown it is part of, not a stopped block.
        currentCoroutineContext().ensureActive()
        null
    }
