package com.helltar.vusan.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/**
 * The turns each conversation has under way, so that something outside them can stop them. Nothing else
 * can: every other path into the agent takes the conversation lock and would wait behind the very turn
 * it is meant to interrupt.
 *
 * A person's own messages are held from the moment they join the line, not only while they run: a stop
 * that lets the next message in the line start reads as a stop that did not work. A scheduled fire is
 * held only while it runs — it has taken nothing until then, and stopping something the person never
 * saw start reads as a bug from either side of the chat.
 */
internal class RunningTurns<K : Any> {

    private val turns = HashMap<K, MutableSet<Job>>()

    suspend fun <T> track(key: K, block: suspend () -> T): T {
        val turn = currentCoroutineContext().job
        synchronized(turns) { turns.getOrPut(key) { mutableSetOf() }.add(turn) }

        try {
            return block()
        } finally {
            // by identity: a turn that already finished must not take along one that came after it.
            synchronized(turns) {
                turns[key]?.let { held ->
                    held.remove(turn)
                    if (held.isEmpty()) turns.remove(key)
                }
            }
        }
    }

    /** Cancels everything that conversation has under way and reports whether there was anything. */
    fun cancel(key: K): Boolean {
        val held = synchronized(turns) { turns[key]?.toList() }.orEmpty()
        held.forEach { it.cancel(CancellationException("stopped by the user")) }

        return held.isNotEmpty()
    }
}
