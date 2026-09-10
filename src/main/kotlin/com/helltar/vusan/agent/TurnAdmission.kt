package com.helltar.vusan.agent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many turns run at once, and how many may wait for a place.
 *
 * A conversation's own lock stops one person from starting two turns and nothing more: a hundred people
 * meant a hundred turns, each an LLM call with its tools, the files it loads and a share of the write
 * lock. What gives way first is the provider's rate limit, and the answer everyone gets is an error.
 *
 * Waiting is the normal answer — a turn holds its typing indicator while it waits, so the chat looks
 * the same as a slow reply. Refusing is for a queue already so long that the answer would arrive after
 * the question stopped mattering.
 */
internal class TurnAdmission(
    private val maxConcurrent: Int,
    private val maxWaiting: Int = maxConcurrent * WAITING_PER_TURN
) {

    private companion object {
        // the ceiling on the queue, as a multiple of what runs at once: a turn is tens of seconds, so
        // this is already minutes of waiting, and past it saying so beats answering too late.
        const val WAITING_PER_TURN = 4
    }

    init {
        require(maxConcurrent > 0) { "Turn admission needs at least one place" }
        require(maxWaiting >= 0) { "Turn admission cannot have a negative queue" }
    }

    private val places = Semaphore(maxConcurrent)
    private val waiting = AtomicInteger()

    /** Runs [turn] when there is room for it, or answers `null` when too many are already waiting. */
    suspend fun <T> admit(turn: suspend () -> T): T? {
        if (places.tryAcquire()) {
            try {
                return turn()
            } finally {
                places.release()
            }
        }

        if (waiting.incrementAndGet() > maxWaiting) {
            waiting.decrementAndGet()
            return null
        }

        try {
            return places.withPermit { turn() }
        } finally {
            waiting.decrementAndGet()
        }
    }

    /**
     * Runs [turn] once there is room, however long that takes. Nobody is watching a queued turn arrive —
     * a scheduled fire, a spooled message — so refusing it would drop work rather than answer late.
     */
    suspend fun <T> admitQueued(turn: suspend () -> T): T = places.withPermit { turn() }
}
