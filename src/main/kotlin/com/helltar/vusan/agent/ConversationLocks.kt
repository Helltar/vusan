package com.helltar.vusan.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One turn per conversation at a time, with a short queue behind it.
 *
 * A turn reads history at start and appends at the end, so two in parallel would each miss the other's
 * exchange. Waiting keeps "now add logging to it" pointed at something: the next turn starts with the
 * previous one in its history. The mutex is fair, so the queue is served in order.
 *
 * The queue is bounded: a person can write faster than a turn runs, and an answer to the tenth message in a
 * row is one nobody waits for.
 */
internal class ConversationLocks<K : Any>(private val maxWaiting: Int) {

    private val locks = HashMap<K, Entry>()

    init {
        require(maxWaiting >= 0) { "A conversation cannot have a negative line" }
    }

    /** Runs [block] once the conversation is free, or returns `null` when its queue is full. */
    suspend fun <T : Any> withLockIfRoom(key: K, block: suspend () -> T): T? {
        val mutex = retain(key, bounded = true) ?: return null

        try {
            return mutex.withLock { block() }
        } finally {
            release(key)
        }
    }

    /**
     * Runs [block] once the conversation is free, however long the queue: a refused scheduled fire or button
     * press would be dropped work, and a refused clear would not clear.
     */
    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val mutex = checkNotNull(retain(key, bounded = false))

        try {
            return mutex.withLock { block() }
        } finally {
            release(key)
        }
    }

    // everyone counts, the mutex holder and all waiters. a bounded arrival is refused when the queue is full,
    // and the count is taken under one monitor so two arrivals never both take the last place.
    private fun retain(key: K, bounded: Boolean): Mutex? =
        synchronized(locks) {
            val entry = locks.getOrPut(key) { Entry() }

            if (bounded && entry.inLine > maxWaiting) {
                null
            } else {
                entry.inLine++
                entry.mutex
            }
        }

    private fun release(key: K) {
        synchronized(locks) {
            val entry = locks[key] ?: return
            if (--entry.inLine <= 0) locks.remove(key)
        }
    }

    private class Entry(val mutex: Mutex = Mutex(), var inLine: Int = 0)
}
