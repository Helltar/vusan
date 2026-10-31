package com.helltar.vusan.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/**
 * The turns each conversation has under way, so something outside them can stop them; every other path into
 * the agent takes the conversation lock and would wait behind the turn it means to interrupt.
 *
 * A person's own messages are held from the moment they queue: a stop that lets the next queued message start
 * reads as a stop that failed. A scheduled fire is held only while it runs: stopping something nobody saw
 * start reads as a bug.
 */
internal class RunningTurns<K : Any> {

    private val turns = HashMap<K, MutableSet<Job>>()

    suspend fun <T> track(key: K, block: suspend () -> T): T {
        val turn = currentCoroutineContext().job
        synchronized(turns) { turns.getOrPut(key) { mutableSetOf() }.add(turn) }

        try {
            return block()
        } finally {
            // by identity: a finished turn must not take a later one along.
            synchronized(turns) {
                turns[key]?.let { held ->
                    held.remove(turn)
                    if (held.isEmpty()) turns.remove(key)
                }
            }
        }
    }

    /** Whether that conversation has anything running or queued. */
    fun holds(key: K): Boolean =
        synchronized(turns) { turns.containsKey(key) }

    /** Whether any conversation [matching] has anything under way. */
    fun holdsAny(matching: (K) -> Boolean): Boolean =
        synchronized(turns) { turns.keys.any(matching) }

    /** Cancels everything that conversation has under way and reports whether there was anything. */
    fun cancel(key: K): Boolean {
        val held = synchronized(turns) { turns[key]?.toList() }.orEmpty()
        held.forEach { it.cancel(CancellationException("stopped by the user")) }

        return held.isNotEmpty()
    }
}
