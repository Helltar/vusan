package com.helltar.vusan.agent

import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.UserRef
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration

/**
 * Who holds the floor in a group, and for how long one person may keep it.
 *
 * One person and the bot alone, answer after answer, is what the rest of the chat scrolls past in the
 * morning, mostly the bot's lines. So the bot stops: after [ANSWERS_PER_STRETCH] answers to one person with
 * nobody else writing in that chat and topic, that person is left unanswered until someone else writes there
 * or [COOLING_PERIOD] passes without an answer. The last answer is marked so the model can round the exchange
 * off instead of falling silent mid-sentence.
 *
 * Anyone else's line hands the floor back, addressed to the bot or not: while others write, a long exchange
 * is one thread among theirs. A private chat is never limited, nor is the configured owner.
 */
class ChatFloor(
    private val exempt: (UserRef) -> Boolean = { false },
    private val clock: () -> Instant = Instant::now,
) {

    enum class Verdict { OPEN, LAST_ANSWER, CLOSED }

    private data class Floor(val chat: ChatRef, val threadId: String?)

    private data class Stretch(val holder: UserRef, val answered: Int, val lastAnsweredAt: Instant)

    private val stretches = ConcurrentHashMap<Floor, Stretch>()

    private val RequestContext.floor: Floor
        get() = Floor(chatRef, chat.threadId)

    /** A person's line reached the chat; anyone but the floor's holder takes it back. */
    fun lineFrom(chat: ChatRef, threadId: String?, speaker: UserRef) {
        stretches.computeIfPresent(Floor(chat, threadId)) { _, stretch -> stretch.takeIf { it.holder == speaker } }
    }

    /** Whether a turn for [context] is answered, counted toward its sender's stretch if so. */
    fun admit(context: RequestContext): Verdict {
        if (context.chat.isPrivate || exempt(context.user)) return Verdict.OPEN

        val now = clock()
        var verdict = Verdict.OPEN

        stretches.compute(context.floor) { _, current ->
            val stretch = current?.takeIf { it.holder == context.user && !it.cooledBy(now) }

            when {
                stretch == null -> Stretch(context.user, answered = 1, lastAnsweredAt = now)

                // an unanswered message does not extend the stretch: the hour runs from the bot's last line,
                // which is what the chat must be free of
                stretch.answered >= ANSWERS_PER_STRETCH -> stretch.also { verdict = Verdict.CLOSED }

                else -> {
                    val answered = stretch.answered + 1
                    if (answered == ANSWERS_PER_STRETCH) verdict = Verdict.LAST_ANSWER
                    Stretch(context.user, answered, lastAnsweredAt = now)
                }
            }
        }

        return verdict
    }

    /**
     * A turn [admit] counted ended without an answer (turned away, silent, failed, stopped), so it does not
     * count. If it was the last, the next message becomes the last answer, so the stretch never closes
     * without the model rounding it off.
     */
    fun unanswered(context: RequestContext) {
        if (context.chat.isPrivate || exempt(context.user)) return

        // the bot's last line keeps its time: the place is given back, the hour is not wound back
        stretches.computeIfPresent(context.floor) { _, stretch ->
            if (stretch.holder != context.user) stretch
            else stretch.copy(answered = stretch.answered - 1).takeIf { it.answered > 0 }
        }
    }

    /**
     * Whether [admit] would turn this person away, counting nothing: for an adapter asking before it pays for
     * the turn. A private chat never holds a stretch.
     */
    fun isClosed(chat: ChatRef, threadId: String?, user: UserRef): Boolean {
        if (exempt(user)) return false

        val stretch = stretches[Floor(chat, threadId)] ?: return false

        return stretch.holder == user && stretch.answered >= ANSWERS_PER_STRETCH && !stretch.cooledBy(clock())
    }

    private fun Stretch.cooledBy(now: Instant): Boolean =
        Duration.between(lastAnsweredAt, now).toKotlinDuration() >= COOLING_PERIOD

    companion object {
        // a little above the 99th percentile of one person's uninterrupted exchanges with the bot over a
        // month of a live group; the longer ones were the nights in question
        const val ANSWERS_PER_STRETCH = 10

        // long enough for the person to have moved on, short enough that a quiet afternoon does not shut
        // someone out until evening
        val COOLING_PERIOD = 1.hours
    }
}
