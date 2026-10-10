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
 * One person and the bot alone, answer after answer with nobody else writing, is what the rest of the
 * chat scrolls past in the morning, and the bot's lines are the bulk of it. So the bot is what stops:
 * once it has answered one person [ANSWERS_PER_STRETCH] times while nobody else wrote in that chat and
 * topic, that person's messages are left unanswered until somebody else writes there or
 * [COOLING_PERIOD] passes without an answer. The last answer of a stretch is marked as such, so the
 * model can round the exchange off itself rather than fall silent mid-sentence.
 *
 * A line from anybody else hands the floor back to the chat whether or not it was for the bot: while
 * others are writing, the chat is awake and a long exchange is one thread among theirs. A private chat
 * has nobody else to read it and is never limited; the owner named in the configuration never is either.
 */
class ChatFloor(
    private val exempt: (UserRef) -> Boolean = { false },
    private val clock: () -> Instant = Instant::now,
) {

    enum class Verdict { OPEN, LAST_ANSWER, CLOSED }

    private data class Floor(val chat: ChatRef, val threadId: String?)

    private data class Stretch(val holder: UserRef, val answered: Int, val lastAnsweredAt: Instant)

    private val stretches = ConcurrentHashMap<Floor, Stretch>()

    /** A person's line reached the chat. Anybody but the one holding the floor takes it back for the chat. */
    fun lineFrom(chat: ChatRef, threadId: String?, speaker: UserRef) {
        stretches.computeIfPresent(Floor(chat, threadId)) { _, stretch -> stretch.takeIf { it.holder == speaker } }
    }

    /** Whether a turn for [context] is answered, counted as one of its sender's stretch when it is. */
    fun admit(context: RequestContext): Verdict {
        if (context.chat.isPrivate || exempt(context.user)) return Verdict.OPEN

        val now = clock()
        var verdict = Verdict.OPEN

        stretches.compute(context.floor) { _, current ->
            val stretch = current?.takeIf { it.holder == context.user && !it.cooledBy(now) }

            when {
                stretch == null -> Stretch(context.user, answered = 1, lastAnsweredAt = now)

                // an unanswered message does not extend the stretch: the hour is counted from the bot's
                // last line, since that is what the chat has to be free of
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

    /** Whether [admit] would turn this turn away, without counting anything. */
    fun isClosed(context: RequestContext): Boolean =
        isClosed(context.chatRef, context.chat.threadId, context.user)

    /** The same, for an adapter asking ahead of building the turn. A private chat never holds a stretch. */
    fun isClosed(chat: ChatRef, threadId: String?, user: UserRef): Boolean {
        if (exempt(user)) return false

        val stretch = stretches[Floor(chat, threadId)] ?: return false

        return stretch.holder == user && stretch.answered >= ANSWERS_PER_STRETCH && !stretch.cooledBy(clock())
    }

    private fun Stretch.cooledBy(now: Instant): Boolean =
        Duration.between(lastAnsweredAt, now).toKotlinDuration() >= COOLING_PERIOD

    private val RequestContext.floor: Floor
        get() = Floor(chatRef, chat.threadId)

    companion object {
        // the 99th percentile of one person's exchanges with the bot measured over a month of a live
        // group, where nothing else was written in between; the longer ones were the nights in question
        const val ANSWERS_PER_STRETCH = 8

        // long enough for the person to have moved on from the conversation, short enough that a quiet
        // afternoon does not keep someone shut out until the evening
        val COOLING_PERIOD = 1.hours
    }
}
