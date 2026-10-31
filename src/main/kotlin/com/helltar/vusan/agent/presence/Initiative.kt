package com.helltar.vusan.agent.presence

import com.helltar.vusan.agent.grouplog.AuthorActivity
import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.neutralizePromptBlocks
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
 * A chat's stickers, as a look shows them and delivery sends them. The messenger's catalog answers both: a
 * look hands back the id it read from the block it was shown, so nothing here holds a sticker's own identity.
 */
interface StickerShortlist {
    suspend fun indexBlockFor(chat: ChatRef): String?
    suspend fun fileIdFor(chat: ChatRef, id: Long): String?
}

/**
 * The bot looking over a group nobody called it into and now and then acting on it: an emoji on a message, a
 * line or a sticker of its own, or, most often, nothing.
 *
 * Code decides whether to look, a model decides what comes of it. A chat is looked at only while people are
 * writing, after a random pause around the configured interval, outside quiet hours, when there is something
 * new and the bot is not already in the conversation. A look sends the group's recent lines to the model
 * unasked, which is why it is opt-in and limited to chats [isAllowed] still admits.
 *
 * It can only add something: a failure, timeout or unreadable answer is silence, and the daily output is
 * bounded whatever the model wants. A person who asked to be left alone is out of reach by code: their lines
 * carry no number, count as nothing new, and the look is told so, since its own line must not be meant for
 * them either.
 *
 * State is process memory; a restart forgets what was looked at and today's count.
 */
class Initiative(
    private val mind: InitiativeMind,
    private val groupLog: GroupLogRepository,
    private val delivery: OutputDelivery,
    private val config: InitiativeConfig,
    private val isAllowed: (ChatRef) -> Boolean,
    private val isAnswering: (ChatRef) -> Boolean = { false },
    private val diary: (suspend (ChatRef) -> String?)? = null,
    private val stickers: StickerShortlist? = null,
    private val leaveAlone: LeaveAloneRepository? = null,
    private val zone: ZoneId = groupLog.zone,
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

    // a chat that refused the bot stays refused until a restart: every later line would fail the same way,
    // each look a wasted model call
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
        // a new chat waits out a pause too, so a restart never has the bot speak up everywhere at once
        val state = states.getOrPut(chat) { ChatState(lastLookAt = startedAt, nextLookAt = now + nextPause()) }

        if (now < state.nextLookAt) return

        state.nextLookAt = now + nextPause()
        state.startDay(LocalDate.ofInstant(now, zone))

        val entries = groupLog.recent(chat, CONTEXT_LINES, since = now - CONTEXT_MAX_AGE)
        val leftAlone = leaveAlone?.activeInChat(chat, now).orEmpty()

        // its own line proves it was here: what stands above has been read, and reacting to it afterward
        // looks like noticing a message it already answered past
        val seenUntil =
            entries.filterNot { it.isFromPerson }.maxOfOrNull { it.sentAt }
                ?.takeIf { it.isAfter(state.lastLookAt) }
                ?: state.lastLookAt

        // lines from someone who asked to be left alone are no reason to look
        val fresh = entries.count { it.isFromPerson && it.sentAt.isAfter(seenUntil) && it.senderId !in leftAlone }

        // the day's count bounds how much it writes, the gap how often; otherwise a lively hour could take
        // the whole day's lines
        val maySpeak =
            state.said < config.maxMessagesPerDay &&
                    state.lastSaidAt?.let { !now.isBefore(it + MIN_GAP_BETWEEN_LINES) } != false
        val mayReact = state.reacted < MAX_REACTIONS_PER_DAY

        val skip =
            when {
                fresh < MIN_FRESH_MESSAGES -> "quiet"
                // a turn under way means the bot is answering somebody here: a line now would arrive beside
                // the answer. its own recent line means it was called in or just spoke up, and a second line
                // would talk over itself
                isAnswering(chat) ||
                        entries.any { !it.isFromPerson && it.sentAt.isAfter(now - OWN_LINE_COOLDOWN) } -> "already talking"
                !maySpeak && !mayReact -> BUDGET_SPENT
                else -> null
            }

        if (skip != null) {
            // the last look stays where it was, so what was said meanwhile is still new. a skip cost no model
            // call, so it retries soon instead of waiting a full pause, which left a chat that keeps calling
            // the bot hardly any looks
            if (skip != BUDGET_SPENT) state.nextLookAt = now + retryPause()

            log.info { "initiative skip: chat=[$chat] reason=[$skip] fresh=[$fresh]" }
            return
        }

        val glance = Glance(entries, seenUntil, zone, leftAlone.keys)
        state.lastLookAt = now

        val input =
            InitiativeInput(
                now = ZonedDateTime.ofInstant(now, zone),
                lines = glance.lines,
                saidToday = state.said,
                maySpeak = maySpeak,
                diary = diary?.invoke(chat),
                leftAlone = leftAloneLines(entries, leftAlone),
                // both serve only a line of its own, so a spent day pays for neither
                quietLately = if (maySpeak) quietLately(chat, now, leftAlone.keys) else emptyList(),
                stickerCatalog = if (maySpeak) stickers?.indexBlockFor(chat) else null,
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

    // returns the part of the log line saying what came of the look
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
                // an anchor the look never offered is dropped, not refused: the line still stands
                val anchor = decision.replyTo?.let(glance::messageIdOf)

                if (!maySpeak) {
                    "action=[say] result=[over budget]"
                } else {
                    state.said++
                    state.lastSaidAt = clock()
                    // the mind writes plain text; delivery reads telegram html
                    send(chat, BotOutput.Text(text.escapeHtml()), anchor)

                    "action=[${if (anchor == null) "say" else "reply"}]" +
                            anchor?.let { " msg=[$it]" }.orEmpty() +
                            " chars=[${text.length}] text=[${text.collapseWhitespaceAndCap(MAX_LOGGED_TEXT_CHARS)}]"
                }
            }

            is InitiativeDecision.Sticker -> sticker(chat, state, glance, decision, maySpeak)
        }

    // a sticker is a message, so it counts as a line: gap and count included
    private suspend fun sticker(
        chat: ChatRef,
        state: ChatState,
        glance: Glance,
        decision: InitiativeDecision.Sticker,
        maySpeak: Boolean,
    ): String {
        if (!maySpeak) return "action=[sticker] result=[over budget]"

        // an id the shortlist never listed, or no shortlist at all, sends nothing
        val fileId =
            stickers?.fileIdFor(chat, decision.id)
                ?: return "action=[sticker] result=[no such sticker] id=[${decision.id}]"
        val anchor = decision.replyTo?.let(glance::messageIdOf)

        state.said++
        state.lastSaidAt = clock()
        send(chat, BotOutput.Sticker(fileId, catalogId = decision.id), anchor)

        return "action=[sticker] id=[${decision.id}]" + anchor?.let { " msg=[$it]" }.orEmpty()
    }

    private suspend fun send(chat: ChatRef, output: BotOutput, anchor: String?) {
        if (delivery.deliverUnprompted(Destination(chat), listOf(output), anchor).isUnreachable) {
            unreachable += chat
            log.warn { "initiative stopped for a chat that no longer accepts the bot: chat=[$chat]" }
        }
    }

    // people who wrote a noticeable share here and have been gone longer than a weekend. asking after
    // somebody is addressing them, so a person who asked to be left alone is not missed out loud
    private suspend fun quietLately(chat: ChatRef, now: Instant, leftAlone: Set<String>): List<String> =
        groupLog.authorActivity(chat, since = now - ABSENCE_LOOKBACK)
            .filter { it.messages >= ABSENCE_MIN_MESSAGES && it.lastSeenAt.isBefore(now - ABSENCE_AFTER) }
            .filterNot { it.senderId in leftAlone }
            .sortedByDescending(AuthorActivity::messages)
            .take(MAX_QUIET_PEOPLE)
            .map { "${it.name} — last wrote ${JavaDuration.between(it.lastSeenAt, now).toDays()} days ago" }

    // the left-alone people in the window, under the names the glance uses; someone with no line in it is
    // omitted
    private fun leftAloneLines(entries: List<GroupLogEntry>, leftAlone: Map<String, Instant>): List<String> =
        entries
            .filter { it.isFromPerson && it.senderId in leftAlone }
            .distinctBy { it.senderId }
            .map { entry ->
                val until = leftAlone.getValue(checkNotNull(entry.senderId))
                "${entry.glanceAuthor()} — asked you not to write to them, until ${LEFT_ALONE_END.format(ZonedDateTime.ofInstant(until, zone))}"
            }

    private fun nextPause(): Duration = config.intervalMinutes.minutes * (PAUSE_MIN_SHARE + random.nextDouble())

    private fun retryPause(): Duration = RETRY_AFTER_SKIP * (1 + random.nextDouble())

    private companion object {
        val TICK = 1.minutes

        // a chat is worth a look only while people are in it; otherwise the bot talks to itself
        val ACTIVE_WITHIN = 15.minutes
        const val MIN_FRESH_MESSAGES = 3
        val OWN_LINE_COOLDOWN = 5.minutes

        // a skipped look retries after this to twice this
        val RETRY_AFTER_SKIP = 5.minutes

        // between two lines of its own in one chat, whatever is left of the day's count; reactions are not
        // held back
        val MIN_GAP_BETWEEN_LINES = 90.minutes

        const val BUDGET_SPENT = "budget spent"

        // a pause runs from half the interval to one and a half of it
        const val PAUSE_MIN_SHARE = 0.5

        // a lively half hour runs past thirty lines, and someone back after a slow afternoon reads further
        // than ninety minutes
        const val CONTEXT_LINES = 60
        val CONTEXT_MAX_AGE = 6.hours

        // the chat model answers this, reasoning included, and nothing waits on it
        val MIND_TIMEOUT = 90.seconds

        const val MAX_REACTIONS_PER_DAY = 20
        const val MAX_SAY_CHARS = 500
        const val MAX_LOGGED_TEXT_CHARS = 200

        val ABSENCE_LOOKBACK = 30.days
        val ABSENCE_AFTER = 3.days
        const val ABSENCE_MIN_MESSAGES = 20L
        const val MAX_QUIET_PEOPLE = 5

        val LEFT_ALONE_END: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val log = KotlinLogging.logger {}
    }
}

/**
 * The recent chat as one look shows it, and the way back from a number the model points at to its message.
 * Message ids are never shown: only lines a person wrote after [seenUntil] (the last look, or the bot's own
 * later line) get a number, so a decision cannot reach past what the look showed. A line by somebody in
 * [leftAlone] never gets one, so no reaction or reply can reach them.
 */
private class Glance(entries: List<GroupLogEntry>, seenUntil: Instant, zone: ZoneId, leftAlone: Set<String>) {

    private val targets = HashMap<Int, String>()

    val lines: List<ChatGlanceLine> =
        entries.mapNotNull { entry ->
            val content = entry.glanceContent() ?: return@mapNotNull null
            val fresh = entry.sentAt.isAfter(seenUntil)

            val number =
                entry.messageId
                    ?.takeIf { fresh && entry.isFromPerson && entry.senderId !in leftAlone }
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

// names, channel titles and words are user text going into a prompt: defused like a turn's transcript, so
// nobody opens a block in it
private fun GroupLogEntry.glanceAuthor(): String =
    (if (isFromPerson) senderUsername ?: senderName ?: UNKNOWN_AUTHOR else OWN_AUTHOR).neutralizePromptBlocks()

private fun GroupLogEntry.glanceContent(): String? {
    val label = descriptor?.let { if (isFromPerson) "[$kind $it]" else "[$it]" }
    val forward = forwardFrom?.let { "[forward from $it]" }

    return listOfNotNull(forward, label, text?.limitTo(MAX_LINE_CHARS))
        .joinToString(" ")
        .neutralizePromptBlocks()
        .takeIf { it.isNotBlank() }
}

private operator fun Instant.minus(duration: Duration): Instant = minus(duration.toJavaDuration())

private operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())
