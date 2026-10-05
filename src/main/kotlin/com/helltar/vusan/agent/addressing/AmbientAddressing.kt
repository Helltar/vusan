package com.helltar.vusan.agent.addressing

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ChatRef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A group message nobody tagged the bot in, as the adapter hands it over: only text a person typed,
 * never an edit, a forward or a command, all of which the adapter has already turned away.
 *
 * [authorWaiting] is true while the author has a turn of their own running or queued in this chat.
 */
data class AmbientCandidate(
    val chat: ChatRef,
    val messageId: String,
    val author: String,
    val text: String,
    val inReplyTo: String? = null,
    val authorWaiting: Boolean = false,
)

/**
 * Whether a group message with no mention, reply or command is still meant for the bot — its name said
 * out loud, or a follow-up to what it just answered.
 *
 * It may only ever add answers. Every mention, reply and command is decided before this is asked, and
 * a failure, a timeout or a chat over its rate all come out as "not addressed", which is exactly how the
 * bot behaved before this existed.
 *
 * Code decides whether to ask, a model decides the answer. A message reaches the model only when it
 * names the bot, when its author is still waiting on the bot, or when the bot spoke within
 * [FOLLOW_UP_WINDOW] and that line is among the few the model is shown; everything else in the chat
 * never leaves the machine. The gate is about privacy before cost.
 */
class AmbientAddressing(
    private val classifier: AddressingClassifier,
    private val groupLog: GroupLogRepository,
    botNames: List<String>,
    private val clock: () -> Instant = Instant::now,
) {

    /** The names a message is checked for, the one the bot's own lines go under first. */
    val botNames: List<String> = botNames.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

    private val namePattern = namePattern(this.botNames)

    private val rateLimit = ChatRateLimit(MAX_CALLS_PER_CHAT, RATE_WINDOW)

    init {
        require(this.botNames.isNotEmpty()) { "Ambient addressing needs at least one name for the bot" }
    }

    suspend fun isAddressed(candidate: AmbientCandidate): Boolean {
        val now = clock()
        val recent = recentEntries(candidate, now) ?: return false
        val gate = gateFor(candidate, recent, now) ?: return false

        if (!rateLimit.tryAcquire(candidate.chat, now)) {
            log.info { "ambient check skipped over the chat's rate: chat=[${candidate.chat}] msg=[${candidate.messageId}]" }
            return false
        }

        val started = TimeSource.Monotonic.markNow()
        val verdict = classify(candidate, recent)

        // no text, ever: the message id finds it in the group log when a verdict needs a second look
        log.info {
            "ambient verdict: chat=[${candidate.chat}] msg=[${candidate.messageId}] gate=[${gate.label}] " +
                    "verdict=[${verdict.label}] ms=[${started.elapsedNow().inWholeMilliseconds}]"
        }

        return verdict == Verdict.ADDRESSED
    }

    private suspend fun recentEntries(candidate: AmbientCandidate, now: Instant): List<GroupLogEntry>? =
        runCatching {
            groupLog.recent(
                chat = candidate.chat,
                limit = CONTEXT_LINES,
                since = now.minusMillis(CONTEXT_MAX_AGE.inWholeMilliseconds),
                excludeMessageId = candidate.messageId,
            )
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "ambient check skipped, the group log could not be read: chat=[${candidate.chat}]" }
        }.getOrNull()

    private fun gateFor(candidate: AmbientCandidate, recent: List<GroupLogEntry>, now: Instant): Gate? {
        val followUpSince = now.minusMillis(FOLLOW_UP_WINDOW.inWholeMilliseconds)

        return when {
            namePattern.containsMatchIn(candidate.text) -> Gate.NAME
            candidate.authorWaiting -> Gate.WAITING
            // a bot line outside the context is one the model could not connect the message to anyway
            recent.any { it.kind == GroupLogEntry.BOT_KIND && !it.sentAt.isBefore(followUpSince) } -> Gate.FOLLOW_UP
            else -> null
        }
    }

    private suspend fun classify(candidate: AmbientCandidate, recent: List<GroupLogEntry>): Verdict {
        val input =
            AddressingInput(
                botNames = botNames,
                recent = recent.mapNotNull { it.toChatLine() },
                message = ChatLine(candidate.author, candidate.text),
                inReplyTo = candidate.inReplyTo,
                botBusyFor = candidate.author.takeIf { candidate.authorWaiting },
            )

        return runCatching {
            withTimeoutOrNull(CLASSIFIER_TIMEOUT) { Verdict.of(classifier.isAddressed(input)) } ?: Verdict.TIMED_OUT
        }.getOrElse {
            it.rethrowIfCancellation()
            log.warn(it) { "ambient classifier failed: chat=[${candidate.chat}] msg=[${candidate.messageId}]" }
            Verdict.FAILED
        }
    }

    private fun GroupLogEntry.toChatLine(): ChatLine? {
        val from =
            if (kind == GroupLogEntry.BOT_KIND) botNames.first()
            else senderName ?: senderUsername ?: UNKNOWN_AUTHOR

        val content = text ?: descriptor?.let { "[$it]" } ?: return null

        return ChatLine(from, content)
    }

    private enum class Gate(val label: String) {
        NAME("name"),
        WAITING("waiting"),
        FOLLOW_UP("follow-up"),
    }

    // everything but a plain yes keeps the bot out, as it was before; they are told apart for the log
    private enum class Verdict(val label: String) {
        ADDRESSED("addressed"),
        NOT_ADDRESSED("not addressed"),
        UNREADABLE("unreadable"),
        TIMED_OUT("timed out"),
        FAILED("failed");

        companion object {
            fun of(answer: Boolean?): Verdict =
                when (answer) {
                    true -> ADDRESSED
                    false -> NOT_ADDRESSED
                    null -> UNREADABLE
                }
        }
    }

    private companion object {
        // how long after the bot's own line a message with no name in it may still be a follow-up to it
        val FOLLOW_UP_WINDOW = 5.minutes

        // what the classifier is shown, the way it was measured: a few lines, none of them old
        const val CONTEXT_LINES = 6
        val CONTEXT_MAX_AGE = 10.minutes

        // the model answered in about a second, with a tail to almost three; a verdict later than this
        // lands after the conversation has moved on
        val CLASSIFIER_TIMEOUT = 5.seconds

        // a chat that runs hot right after the bot speaks would otherwise send the model every line of it
        const val MAX_CALLS_PER_CHAT = 20
        val RATE_WINDOW = 1.minutes

        const val UNKNOWN_AUTHOR = "someone"

        val log = KotlinLogging.logger {}
    }
}

// anchored at the start of a word only, so an ending the language adds to the name still matches while
// the same letters inside a longer word do not. an `@` in front makes it a username, and a mention of the
// bot's own was answered before this was ever asked.
internal fun namePattern(names: List<String>): Regex =
    Regex(
        "(?<![\\p{L}\\p{N}_@])(?:" + names.joinToString("|") { Regex.escape(it) } + ")",
        RegexOption.IGNORE_CASE,
    )

/** At most [max] calls per chat in any [window]; a call over it is refused rather than queued. */
internal class ChatRateLimit(private val max: Int, private val window: Duration) {

    private val calls = HashMap<ChatRef, ArrayDeque<Instant>>()

    fun tryAcquire(chat: ChatRef, now: Instant): Boolean =
        synchronized(calls) {
            val recent = calls.getOrPut(chat, ::ArrayDeque)
            val cutoff = now.minusMillis(window.inWholeMilliseconds)

            while (recent.firstOrNull()?.isAfter(cutoff) == false) recent.removeFirst()

            if (recent.size >= max) return false

            recent.addLast(now)
            true
        }
}
