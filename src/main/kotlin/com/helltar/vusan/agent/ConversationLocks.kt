package com.helltar.vusan.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One turn per conversation at a time, and a short line behind it.
 *
 * A turn reads the history when it starts and appends to it when it ends, so two running side by side
 * would each answer without the other's exchange. Waiting costs a little time and keeps "now add logging
 * to it" pointed at something: the turn behind starts with the one ahead already in its history. The
 * mutex is fair, so the line is served in the order it formed.
 *
 * The line is bounded because a person can write faster than a turn runs, and an answer to the tenth
 * message in a row is an answer nobody is waiting for any more.
 */
internal class ConversationLocks<K : Any>(private val maxWaiting: Int) {

    private val locks = HashMap<K, Entry>()

    init {
        require(maxWaiting >= 0) { "A conversation cannot have a negative line" }
    }

    /** Runs [block] once the conversation is free, or answers `null` when its line is already full. */
    suspend fun <T : Any> withLockIfRoom(key: K, block: suspend () -> T): T? {
        val mutex = retain(key, bounded = true) ?: return null

        try {
            return mutex.withLock { block() }
        } finally {
            release(key)
        }
    }

    /**
     * Runs [block] once the conversation is free, however long its line is: a scheduled fire or a pressed
     * button that was refused would be work dropped, and a clear that was refused would not clear.
     */
    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val mutex = checkNotNull(retain(key, bounded = false))

        try {
            return mutex.withLock { block() }
        } finally {
            release(key)
        }
    }

    // everyone in the line is counted, whatever brought them: the one holding the mutex and all who wait
    // for it. a bounded arrival is turned away when those ahead already fill the line, and the count is
    // taken under one monitor, so two arrivals can never both take the last place.
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
