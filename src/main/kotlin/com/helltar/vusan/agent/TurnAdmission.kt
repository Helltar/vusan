package com.helltar.vusan.agent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many turns run at once, and how many may wait for a place.
 *
 * A conversation's lock only stops one person starting two turns: a hundred people meant a hundred turns,
 * each an LLM call with tools, loaded files and a share of the write lock. The provider's rate limit gives
 * first, and everyone gets an error.
 *
 * Waiting is the normal answer: a waiting turn holds its typing indicator, so it looks like a slow reply.
 * Refusing is for a queue so long the answer would come after the question stopped mattering.
 */
internal class TurnAdmission(
    private val maxConcurrent: Int,
    private val maxWaiting: Int = maxConcurrent * WAITING_PER_TURN,
) {

    init {
        require(maxConcurrent > 0) { "Turn admission needs at least one place" }
        require(maxWaiting >= 0) { "Turn admission cannot have a negative queue" }
    }

    private val places = Semaphore(maxConcurrent)
    private val waiting = AtomicInteger()

    /** Runs [turn] when there is room, or returns `null` when too many are already waiting. */
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
     * Runs [turn] once there is room, however long. Nobody watches a queued turn arrive (a scheduled fire, a
     * spooled message), so refusing would drop work rather than answer late.
     */
    suspend fun <T> admitQueued(turn: suspend () -> T): T = places.withPermit { turn() }

    private companion object {
        // the queue's ceiling as a multiple of what runs at once: a turn takes tens of seconds, so this is
        // already minutes, and past it saying so beats answering too late.
        const val WAITING_PER_TURN = 4
    }
}
