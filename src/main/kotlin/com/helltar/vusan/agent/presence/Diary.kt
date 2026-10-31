package com.helltar.vusan.agent.presence

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.grouplog.dayEntries
import com.helltar.vusan.agent.grouplog.renderedDay
import com.helltar.vusan.agent.neutralizePromptBlocks
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ChatRef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.time.Duration.Companion.minutes

/**
 * The bot's memory of a group's days: after a day closes, one entry about it in the bot's own voice from that
 * day's transcript, shown back on later turns.
 *
 * It lets a conversation pick up where yesterday left off: history is one person's exchanges with the bot and
 * memory is facts someone asked to keep; neither holds what the chat was like yesterday.
 *
 * Only a closed day is written. An entry is written unasked, sending a whole day of a chat to the model, so
 * only chats [isAllowed] still admits are read. It is also what `readGroupLog` gives for the closed days of a
 * window too wide to quote.
 *
 * Once a week or month is over, its entries are read again into one look-back, so what mattered weeks ago
 * still reaches a turn shown only the last few days. Entries and look-backs last until the chat's transcript
 * is cleared, outliving its retention.
 */
class Diary(
    private val writer: DiaryWriter,
    private val repository: DiaryRepository,
    private val groupLog: GroupLogRepository,
    private val isAllowed: (ChatRef) -> Boolean,
    private val zone: ZoneId = groupLog.zone,
    private val clock: () -> Instant = Instant::now,
) {

    // a day that keeps failing is given up on rather than retried every pass until midnight
    private val attempts = HashMap<Pair<ChatRef, LocalDate>, Int>()
    private val lookBackAttempts = HashMap<Pair<ChatRef, DiarySpan>, Int>()

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            log.info { "diary started: yesterday is written up every ${PASS_INTERVAL.inWholeMinutes}m, where it is missing" }

            while (true) {
                runCatching { catchUp() }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.warn(it) { "diary pass failed; the next one tries again" }
                    }

                delay(PASS_INTERVAL)
            }
        }

    /**
     * Writes yesterday's entry for every chat with a day worth one and no entry yet, then the look-backs over
     * the last settled week and month.
     */
    suspend fun catchUp() {
        val today = LocalDate.ofInstant(clock(), zone)
        val day = today.minusDays(1)
        val chats = groupLog.activeChats(day.startOfDay(), today.startOfDay().minusMillis(1))

        attempts.keys.removeAll { it.second < day }

        chats.filter(isAllowed).forEach { chat ->
            runCatching { writeIfMissing(chat, day) }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.warn(it) { "diary entry failed: chat=[$chat] day=[$day]" }
                }
        }

        // a day's entry is written only during the next day, so the day before yesterday is the last whose
        // entry can no longer appear, and a span ending there is complete
        writeLookBacks(settled = day.minusDays(1))
    }

    /**
     * The text of a `<diary>` block for [chat], or `null` when empty: the newest month and week look-backs,
     * then the last few entries of the past week, oldest first.
     */
    suspend fun blockFor(chat: ChatRef): String? {
        val today = LocalDate.ofInstant(clock(), zone)
        val entries = repository.since(chat, today.minusDays(KEPT_DAYS)).takeLast(SHOWN_ENTRIES)

        val lookBacks =
            DiaryPeriod.entries
                .mapNotNull { repository.latestLookBack(chat, it, before = today) }
                .sortedBy { it.span.firstDay }

        return renderDiary(entries, lookBacks).takeIf { it.isNotEmpty() }
    }

    private suspend fun writeIfMissing(chat: ChatRef, day: LocalDate) {
        val key = chat to day

        if (repository.has(chat, day) || (attempts[key] ?: 0) >= MAX_ATTEMPTS) return

        val entries = groupLog.dayEntries(chat, day, zone)
        val written = entries.count { it.kind != GroupLogEntry.BOT_KIND }

        // a closed day gains no messages, so one too thin is not read again
        attempts[key] = if (written < MIN_DAY_MESSAGES) MAX_ATTEMPTS else (attempts[key] ?: 0) + 1

        if (written < MIN_DAY_MESSAGES) return

        val transcript = entries.renderedDay(zone).text
        val earlier = repository.since(chat, day.minusDays(KEPT_DAYS)).takeLast(EARLIER_ENTRIES)
        val content = writer.write(day, transcript, earlier) ?: return

        repository.store(chat, DiaryEntry(day, content))

        log.info { "diary entry written: chat=[$chat] day=[$day] messages=[$written] chars=[${content.length}]" }
    }

    private suspend fun writeLookBacks(settled: LocalDate) {
        val spans = DiaryPeriod.entries.map { it.lastEndingBy(settled) }

        lookBackAttempts.keys.retainAll { it.second in spans }

        spans.forEach { span ->
            repository.chatsWithEntries(span.firstDay, span.lastDay).filter(isAllowed).forEach { chat ->
                runCatching { writeLookBackIfMissing(chat, span) }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.warn(it) { "diary look-back failed: chat=[$chat] period=[${span.period}] from=[${span.firstDay}]" }
                    }
            }
        }
    }

    private suspend fun writeLookBackIfMissing(chat: ChatRef, span: DiarySpan) {
        val key = chat to span

        if (repository.hasLookBack(chat, span) || (lookBackAttempts[key] ?: 0) >= MAX_ATTEMPTS) return

        val entries = repository.between(chat, span.firstDay, span.lastDay)

        // with a single day a look-back would only retell that day's entry
        lookBackAttempts[key] = if (entries.size < MIN_LOOK_BACK_ENTRIES) MAX_ATTEMPTS else (lookBackAttempts[key] ?: 0) + 1

        if (entries.size < MIN_LOOK_BACK_ENTRIES) return

        val earlier = repository.latestLookBack(chat, span.period, before = span.firstDay)
        val content = writer.lookBack(span, entries, earlier) ?: return

        repository.storeLookBack(chat, DiaryLookBack(span, content))

        log.info {
            "diary look-back written: chat=[$chat] period=[${span.period}] from=[${span.firstDay}] " +
                    "entries=[${entries.size}] chars=[${content.length}]"
        }
    }

    private fun LocalDate.startOfDay(): Instant = atStartOfDay(zone).toInstant()

    private companion object {
        val PASS_INTERVAL = 15.minutes

        // a day of a few stray lines is not worth remembering, and an entry would be padding
        const val MIN_DAY_MESSAGES = 15

        const val MAX_ATTEMPTS = 3
        const val MIN_LOOK_BACK_ENTRIES = 2

        // every group turn carries these, so few, from the last week only: a chat quiet for a few days still
        // gets them, one quiet longer only its look-backs
        const val SHOWN_ENTRIES = 3
        const val EARLIER_ENTRIES = 2
        const val KEPT_DAYS = 7L

        val log = KotlinLogging.logger {}
    }
}

/** The newest span of this period ending on or before [day]. */
internal fun DiaryPeriod.lastEndingBy(day: LocalDate): DiarySpan {
    val next = day.plusDays(1)

    return when (this) {
        DiaryPeriod.WEEK -> DiarySpan(this, next.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1))
        DiaryPeriod.MONTH -> DiarySpan(this, next.withDayOfMonth(1).minusMonths(1))
    }
}

internal fun DiarySpan.label(): String =
    when (period) {
        DiaryPeriod.WEEK -> "week $firstDay..$lastDay"
        DiaryPeriod.MONTH -> "month ${YearMonth.from(firstDay)}"
    }

// an entry retells what people said, so it is defused like the transcript it came from.
internal fun renderDiary(entries: List<DiaryEntry>, lookBacks: List<DiaryLookBack> = emptyList()): String =
    (lookBacks.map { it.span.label() to it.content } + entries.map { it.day.toString() to it.content })
        .joinToString("\n\n") { (label, content) -> "$label: ${content.neutralizePromptBlocks()}" }
