package com.helltar.vusan.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/**
 * The turn each conversation is running, so that something outside it can stop it. Nothing else can:
 * every other path into the agent takes the conversation lock and would wait behind the very turn it is
 * meant to interrupt.
 *
 * Only a turn that is actually running is held here. One queued behind it has taken nothing yet, and
 * stopping something that never started reads as a bug from either side of the chat.
 */
internal class RunningTurns<K : Any> {

    private val turns = HashMap<K, Job>()

    suspend fun <T> track(key: K, block: suspend () -> T): T {
        val turn = currentCoroutineContext().job
        synchronized(turns) { turns[key] = turn }

        try {
            return block()
        } finally {
            // by identity: a turn that already finished must not cancel the one that replaced it.
            synchronized(turns) { turns.remove(key, turn) }
        }
    }

    /** Cancels that conversation's turn and reports whether there was one to cancel. */
    fun cancel(key: K): Boolean {
        val turn = synchronized(turns) { turns[key] } ?: return false
        turn.cancel(CancellationException("stopped by the user"))
        return true
    }
}
