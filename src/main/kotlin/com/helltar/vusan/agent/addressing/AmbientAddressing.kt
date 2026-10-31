package com.helltar.vusan.agent.addressing

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.presence.LeaveAloneRepository
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.llm.AddressingConfig
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.UserRef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * An untagged group message as the adapter hands it over: only text a person typed, never a forward or a
 * command, which the adapter already turned away.
 *
 * [author] is the name the classifier sees, [sender] the qualified identity for what is keyed on the person.
 * [authorWaiting] is true while the author has a turn running or queued in this chat. [edited] marks an edit
 * of a just-sent message, asked about only when it names the bot.
 */
data class AmbientCandidate(
    val chat: ChatRef,
    val messageId: String,
    val author: String,
    val sender: UserRef,
    val text: String,
    val inReplyTo: String? = null,
    val authorWaiting: Boolean = false,
    val edited: Boolean = false,
)

/**
 * Whether a group message with no mention, reply or command is still meant for the bot: its name said aloud,
 * or a follow-up to what it just answered.
 *
 * It can only add answers. Mentions, replies and commands are decided first, and a failure, timeout or a chat
 * over its rate all mean "not addressed", which is how the bot behaved without this.
 *
 * Code decides whether to ask, a model decides the answer. A message reaches the model only when it names the
 * bot, its author is still waiting on the bot, or the bot spoke within [FOLLOW_UP_WINDOW] and that line is in
 * the context shown; an edit gets only the first. Nothing else in the chat leaves the machine: the gate is
 * about privacy before cost.
 *
 * The follow-up door opens on the bot's own guess, so it stays shut for someone who asked to be left alone.
 * Naming the bot is speaking to it, and that door stays open to them.
 */
class AmbientAddressing(
    private val classifier: AddressingClassifier,
    private val groupLog: GroupLogRepository,
    botNames: List<String>,
    private val leaveAlone: LeaveAloneRepository? = null,
    private val clock: () -> Instant = Instant::now,
) {

    /** The names a message is checked for, the bot's own display name first. */
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

        if (gate == Gate.FOLLOW_UP && leftAlone(candidate, now)) {
            log.info { "ambient check skipped, the author asked to be left alone: chat=[${candidate.chat}] msg=[${candidate.messageId}]" }
            return false
        }

        if (!rateLimit.tryAcquire(candidate.chat, now)) {
            log.info { "ambient check skipped over the chat's rate: chat=[${candidate.chat}] msg=[${candidate.messageId}]" }
            return false
        }

        val started = TimeSource.Monotonic.markNow()
        val verdict = classify(candidate, recent)

        // no text: the message id finds it in the group log when a verdict needs a second look
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

    // an unreadable store keeps the bot out, like every other failure here
    private suspend fun leftAlone(candidate: AmbientCandidate, now: Instant): Boolean =
        runCatching { leaveAlone?.activeUntil(candidate.chat, candidate.sender, now) != null }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "ambient check skipped, the leave-alone store could not be read: chat=[${candidate.chat}]" }
            }
            .getOrDefault(true)

    private fun gateFor(candidate: AmbientCandidate, recent: List<GroupLogEntry>, now: Instant): Gate? {
        val followUpSince = now.minusMillis(FOLLOW_UP_WINDOW.inWholeMilliseconds)

        return when {
            namePattern.containsMatchIn(candidate.text) -> Gate.NAME
            // an edit is someone adding the forgotten name; through the other doors every typo fixed near the
            // bot's answer would be judged
            candidate.edited -> null
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
            withTimeoutOrNull(AddressingConfig.VERDICT_TIMEOUT) { Verdict.of(classifier.isAddressed(input)) } ?: Verdict.TIMED_OUT
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

    // everything but a plain yes keeps the bot out; the kinds are told apart for the log
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
        // how long after the bot's line an unnamed message may still be a follow-up
        val FOLLOW_UP_WINDOW = 5.minutes

        // what the classifier is shown, as measured: a few recent lines
        const val CONTEXT_LINES = 6
        val CONTEXT_MAX_AGE = 10.minutes

        // otherwise a chat running hot right after the bot speaks would send the model every line
        const val MAX_CALLS_PER_CHAT = 20
        val RATE_WINDOW = 1.minutes

        const val UNKNOWN_AUTHOR = "someone"

        val log = KotlinLogging.logger {}
    }
}

// anchored at a word start only, so an inflected ending still matches while the letters inside a longer word
// do not. `@` makes it a username, and a mention of the bot's was answered before this is asked.
internal fun namePattern(names: List<String>): Regex =
    Regex(
        "(?<![\\p{L}\\p{N}_@])(?:" + names.joinToString("|") { Regex.escape(it) } + ")",
        RegexOption.IGNORE_CASE,
    )

/** At most [max] calls per chat in any [window]; one over it is refused, not queued. */
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
