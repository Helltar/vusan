package com.helltar.vusan.agent.presence

import com.helltar.vusan.agent.grouplog.AuthorActivity
import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.escapeHtml
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.InitiativeConfig
import com.helltar.vusan.delivery.Destination
import com.helltar.vusan.delivery.OutputDelivery
import com.helltar.vusan.outbox.ALLOWED_REACTION_EMOJI
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.normalizeReactionEmoji
import com.helltar.vusan.request.ChatRef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration as JavaDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

/**
 * The bot looking over a group nobody called it into, and now and then doing something about it: an
 * emoji on a message, a line of its own, or — most of the time — nothing.
 *
 * Code decides whether to look, a model decides what comes of it. A chat is looked at only while people
 * are writing in it, after a pause drawn at random around the configured interval, outside the quiet
 * hours, when there is something new since the last look and the bot is not already in the
 * conversation — neither answering somebody there right now nor just done with it. What the model then
 * sees is the chat's recent lines, so unlike everything else the bot
 * does, a look sends a group's conversation to the model with nobody having asked — which is why it is
 * switched on deliberately, and only for chats [isAllowed] still admits.
 *
 * It may only ever add something. A failure, a timeout or an unreadable answer is a look that ended in
 * silence, and what it writes in a day is bounded whatever the model would like.
 *
 * The state is process memory: a restart forgets what was looked at and what today's count stood at.
 */
class Initiative(
    private val mind: InitiativeMind,
    private val groupLog: GroupLogRepository,
    private val delivery: OutputDelivery,
    private val config: InitiativeConfig,
    private val isAllowed: (ChatRef) -> Boolean,
    private val isAnswering: (ChatRef) -> Boolean = { false },
    private val diary: (suspend (ChatRef) -> String?)? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
    private val random: Random = Random.Default,
) {

    private class ChatState(var lastLookAt: Instant, var nextLookAt: Instant) {
        var day: LocalDate? = null
        var said = 0
        var reacted = 0
        var lastSaidAt: Instant? = null

        fun startDay(today: LocalDate) {
            if (day == today) return

            day = today
            said = 0
            reacted = 0
        }
    }

    private val startedAt = clock()
    private val states = HashMap<ChatRef, ChatState>()

    // a chat that refused the bot outright stays refused until a restart: every later line would fail
    // the same way, and each look before it is a model call spent on nothing
    private val unreachable = HashSet<ChatRef>()

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            log.info {
                "initiative started: a look about every ${config.intervalMinutes}m per active chat, " +
                        "at most ${config.maxMessagesPerDay} message(s) a day"
            }

            while (true) {
                runCatching { tick() }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.warn(it) { "initiative pass failed; the next one tries again" }
                    }

                delay(TICK)
            }
        }

    /** One pass over the chats people are writing in right now. */
    suspend fun tick() {
        val now = clock()

        if (now.atZone(zone).hour in config.quietHours) return

        groupLog.activeChats(now - ACTIVE_WITHIN, now)
            .filter { isAllowed(it) && it !in unreachable }
            .forEach { chat ->
                runCatching { glanceAt(chat, now) }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.warn(it) { "initiative look failed: chat=[$chat]" }
                    }
            }
    }

    private suspend fun glanceAt(chat: ChatRef, now: Instant) {
        // a chat seen for the first time waits out a pause like any other, so a restart is never
        // followed by the bot speaking up everywhere at once
        val state = states.getOrPut(chat) { ChatState(lastLookAt = startedAt, nextLookAt = now + nextPause()) }

        if (now < state.nextLookAt) return

        state.nextLookAt = now + nextPause()
        state.startDay(LocalDate.ofInstant(now, zone))

        val entries = groupLog.recent(chat, CONTEXT_LINES, since = now - CONTEXT_MAX_AGE)

        // a line of its own is proof it was here, whoever that line was for: what stands above it has
        // been read, and reacting to it afterwards looks like noticing a message it already answered past
        val seenUntil =
            entries.filterNot { it.isFromPerson }.maxOfOrNull { it.sentAt }
                ?.takeIf { it.isAfter(state.lastLookAt) }
                ?: state.lastLookAt

        val fresh = entries.count { it.isFromPerson && it.sentAt.isAfter(seenUntil) }

        // the day's count bounds how much it writes, the gap how often: without it a lively hour could
        // take the whole day's lines one look after another
        val maySpeak =
            state.said < config.maxMessagesPerDay &&
                    state.lastSaidAt?.let { !now.isBefore(it + MIN_GAP_BETWEEN_LINES) } != false
        val mayReact = state.reacted < MAX_REACTIONS_PER_DAY

        val skip =
            when {
                fresh < MIN_FRESH_MESSAGES -> "quiet"
                // a turn under way is the bot answering somebody here and its line not written yet: to a look
                // the request stands unanswered, and a line of its own now would arrive beside the answer.
                // its own line this recently means somebody called it in, or it just spoke up: either way
                // the conversation already has it, and a second voice of its own would be talking over itself
                isAnswering(chat) ||
                        entries.any { !it.isFromPerson && it.sentAt.isAfter(now - OWN_LINE_COOLDOWN) } -> "already talking"
                !maySpeak && !mayReact -> BUDGET_SPENT
                else -> null
            }

        if (skip != null) {
            // the last look is left where it was, so what was said meanwhile is still new next time.
            // a skipped look cost no model call, so it comes back soon instead of waiting out a whole
            // pause: in a chat that keeps calling the bot in, a full pause after every skip left it
            // hardly any look at all
            if (skip != BUDGET_SPENT) state.nextLookAt = now + retryPause()

            log.info { "initiative skip: chat=[$chat] reason=[$skip] fresh=[$fresh]" }
            return
        }

        val glance = Glance(entries, seenUntil, zone)
        state.lastLookAt = now

        val input =
            InitiativeInput(
                now = ZonedDateTime.ofInstant(now, zone),
                lines = glance.lines,
                saidToday = state.said,
                maySpeak = maySpeak,
                diary = diary?.invoke(chat),
                quietLately = if (maySpeak) quietLately(chat, now) else emptyList(),
            )

        val started = TimeSource.Monotonic.markNow()
        val decision = decide(chat, input)
        val outcome = act(chat, state, glance, decision, maySpeak, mayReact)

        log.info {
            "initiative look: chat=[$chat] fresh=[$fresh] said=[${state.said}/${config.maxMessagesPerDay}] " +
                    "reacted=[${state.reacted}/$MAX_REACTIONS_PER_DAY] $outcome " +
                    "ms=[${started.elapsedNow().inWholeMilliseconds}]" +
                    decision?.why?.let { " why=[$it]" }.orEmpty()
        }
    }

    private suspend fun decide(chat: ChatRef, input: InitiativeInput): InitiativeDecision? =
        runCatching { withTimeoutOrNull(MIND_TIMEOUT) { mind.decide(input) } }
            .getOrElse {
                it.rethrowIfCancellation()
                log.warn(it) { "initiative decision failed: chat=[$chat]" }
                null
            }

    // answers the part of the log line that says what came of the look
    private suspend fun act(
        chat: ChatRef,
        state: ChatState,
        glance: Glance,
        decision: InitiativeDecision?,
        maySpeak: Boolean,
        mayReact: Boolean,
    ): String =
        when (decision) {
            null -> "action=[none] result=[no readable decision]"

            is InitiativeDecision.Silent -> "action=[silent]"

            is InitiativeDecision.React -> {
                val target = glance.messageIdOf(decision.target)
                val emoji = normalizeReactionEmoji(decision.emoji)

                when {
                    !mayReact -> "action=[react] result=[over budget]"
                    target == null -> "action=[react] result=[no such target] target=[${decision.target}]"
                    emoji !in ALLOWED_REACTION_EMOJI -> "action=[react] result=[emoji not allowed] emoji=[$emoji]"

                    else -> {
                        state.reacted++
                        send(chat, BotOutput.Reaction(target, emoji), anchor = null)
                        "action=[react] msg=[$target] emoji=[$emoji]"
                    }
                }
            }

            is InitiativeDecision.Say -> {
                val text = decision.text.limitTo(MAX_SAY_CHARS)
                // an anchor the look never offered is dropped rather than refused: the line still stands
                val anchor = decision.replyTo?.let(glance::messageIdOf)

                if (!maySpeak) {
                    "action=[say] result=[over budget]"
                } else {
                    state.said++
                    state.lastSaidAt = clock()
                    // the mind writes plain text, and delivery reads text as telegram html
                    send(chat, BotOutput.Text(text.escapeHtml()), anchor)

                    "action=[${if (anchor == null) "say" else "reply"}]" +
                            anchor?.let { " msg=[$it]" }.orEmpty() +
                            " chars=[${text.length}] text=[${text.collapseWhitespaceAndCap(MAX_LOGGED_TEXT_CHARS)}]"
                }
            }
        }

    private suspend fun send(chat: ChatRef, output: BotOutput, anchor: String?) {
        if (delivery.deliverUnprompted(Destination(chat), listOf(output), anchor).isUnreachable) {
            unreachable += chat
            log.warn { "initiative stopped for a chat that no longer accepts the bot: chat=[$chat]" }
        }
    }

    // people who used to write here and stopped: enough of a share that their silence is noticeable,
    // gone long enough that it is not just a weekend
    private suspend fun quietLately(chat: ChatRef, now: Instant): List<String> =
        groupLog.authorActivity(chat, since = now - ABSENCE_LOOKBACK)
            .filter { it.messages >= ABSENCE_MIN_MESSAGES && it.lastSeenAt.isBefore(now - ABSENCE_AFTER) }
            .sortedByDescending(AuthorActivity::messages)
            .take(MAX_QUIET_PEOPLE)
            .map { "${it.name} — last wrote ${JavaDuration.between(it.lastSeenAt, now).toDays()} days ago" }

    private fun nextPause(): Duration = config.intervalMinutes.minutes * (PAUSE_MIN_SHARE + random.nextDouble())

    private fun retryPause(): Duration = RETRY_AFTER_SKIP * (1 + random.nextDouble())

    private companion object {
        val TICK = 1.minutes

        // a chat is worth a look only while people are in it: a line dropped into a room everyone has
        // left is the bot talking to itself
        val ACTIVE_WITHIN = 15.minutes
        const val MIN_FRESH_MESSAGES = 3
        val OWN_LINE_COOLDOWN = 5.minutes

        // a skipped look is tried again after this much to twice this much
        val RETRY_AFTER_SKIP = 5.minutes

        // between two lines of its own in one chat, whatever is left of the day's count; a reaction is
        // not a line and is not held back by it
        val MIN_GAP_BETWEEN_LINES = 90.minutes

        const val BUDGET_SPENT = "budget spent"

        // a pause runs from half the interval to one and a half of it
        const val PAUSE_MIN_SHARE = 0.5

        // a lively half hour between two looks runs past thirty lines, and someone who opens a chat
        // after a slow afternoon reads back further than an hour and a half
        const val CONTEXT_LINES = 60
        val CONTEXT_MAX_AGE = 6.hours

        // the chat model answers this, reasoning and all, and nothing is waiting on it
        val MIND_TIMEOUT = 90.seconds

        const val MAX_REACTIONS_PER_DAY = 20
        const val MAX_SAY_CHARS = 500
        const val MAX_LOGGED_TEXT_CHARS = 200

        val ABSENCE_LOOKBACK = 30.days
        val ABSENCE_AFTER = 3.days
        const val ABSENCE_MIN_MESSAGES = 20L
        const val MAX_QUIET_PEOPLE = 5

        val log = KotlinLogging.logger {}
    }
}

/**
 * The recent chat as one look shows it, and the way back from a number the model points at to the
 * message it stands for. Message ids themselves are never shown: a number can only name a line a person
 * wrote after [seenUntil] — the last look, or the bot's own last line when that came later — so a
 * decision cannot reach past what this look put in front of it.
 */
private class Glance(entries: List<GroupLogEntry>, seenUntil: Instant, zone: ZoneId) {

    private val targets = HashMap<Int, String>()

    val lines: List<ChatGlanceLine> =
        entries.mapNotNull { entry ->
            val content = entry.glanceContent() ?: return@mapNotNull null
            val fresh = entry.sentAt.isAfter(seenUntil)

            val number =
                entry.messageId
                    ?.takeIf { fresh && entry.isFromPerson }
                    ?.let { id -> (targets.size + 1).also { targets[it] = id } }

            ChatGlanceLine(
                number = number,
                time = TIME.format(ZonedDateTime.ofInstant(entry.sentAt, zone)),
                author = entry.glanceAuthor(),
                content = content,
                fresh = fresh,
            )
        }

    fun messageIdOf(number: Int): String? = targets[number]

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

private const val MAX_LINE_CHARS = 300
private const val OWN_AUTHOR = "you"
private const val UNKNOWN_AUTHOR = "someone"

private val GroupLogEntry.isFromPerson: Boolean
    get() = kind != GroupLogEntry.BOT_KIND

private fun GroupLogEntry.glanceAuthor(): String =
    if (isFromPerson) senderUsername ?: senderName ?: UNKNOWN_AUTHOR else OWN_AUTHOR

private fun GroupLogEntry.glanceContent(): String? {
    val label = descriptor?.let { if (isFromPerson) "[$kind $it]" else "[$it]" }
    val forward = forwardFrom?.let { "[forward from $it]" }

    return listOfNotNull(forward, label, text?.limitTo(MAX_LINE_CHARS)).joinToString(" ").takeIf { it.isNotBlank() }
}

private operator fun Instant.minus(duration: Duration): Instant = minus(duration.toJavaDuration())

private operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())
