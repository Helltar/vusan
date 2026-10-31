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
 * Runs [block] in its own job and returns `null` when something outside canceled that job. Cancel-by-name
 * from outside (`/stop` ending a running turn) cancels whatever coroutine the call sits in; a caller with
 * more to do (the task scheduler's loop) hands the block a job to be canceled instead. The caller's own
 * cancellation still wins: a shutdown is not a stopped turn.
 */
internal suspend fun <T> runInOwnJob(block: suspend () -> T): T? =

    try {
        coroutineScope { async { block() }.await() }
    } catch (stopped: CancellationException) {
        // when the caller is canceled too, this is a shutdown, not a stopped block.
        currentCoroutineContext().ensureActive()
        null
    }
