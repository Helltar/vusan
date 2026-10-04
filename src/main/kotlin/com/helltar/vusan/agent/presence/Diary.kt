package com.helltar.vusan.agent.presence

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.grouplog.renderGroupLog
import com.helltar.vusan.agent.neutralizePromptBlocks
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ChatRef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * The bot's memory of the days a group has had: after a day closes, one entry about it, written in the
 * bot's own voice from that day's transcript, and shown back to it on the turns that follow.
 *
 * It is what lets a conversation pick up where yesterday left off. History is one person's exchanges
 * with the bot, and durable memory is facts somebody asked to be kept; neither holds what the chat was
 * like the day before.
 *
 * Only a closed day is written, for the reason only a closed day is digested: today is still being
 * added to. Unlike a digest an entry is written without anyone asking, so a whole day of a chat goes
 * to the model unprompted — which is why this is switched on deliberately and only reads chats that
 * [isAllowed] still admits.
 */
class Diary(
    private val writer: DiaryWriter,
    private val repository: DiaryRepository,
    private val groupLog: GroupLogRepository,
    private val isAllowed: (ChatRef) -> Boolean,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
) {

    // a day that keeps failing is given up on rather than asked about every pass until midnight
    private val attempts = HashMap<Pair<ChatRef, LocalDate>, Int>()

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

    /** Writes yesterday's entry for every chat that had a day worth one and has no entry yet. */
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
    }

    /** The last few entries of [chat] as the text of a `<diary>` block, or `null` when there are none. */
    suspend fun blockFor(chat: ChatRef): String? {
        val since = LocalDate.ofInstant(clock(), zone).minusDays(KEPT_DAYS)

        return repository.since(chat, since).takeLast(SHOWN_ENTRIES).takeIf { it.isNotEmpty() }?.let(::renderDiary)
    }

    /** Retention: an entry older than anything [blockFor] would show has no reader left. */
    suspend fun pruneExpired(): Int =
        repository.pruneBefore(LocalDate.ofInstant(clock(), zone).minusDays(KEPT_DAYS))

    private suspend fun writeIfMissing(chat: ChatRef, day: LocalDate) {
        val key = chat to day

        if (repository.has(chat, day) || (attempts[key] ?: 0) >= MAX_ATTEMPTS) return

        val entries = groupLog.readWindow(chat, day.startOfDay(), day.plusDays(1).startOfDay().minusMillis(1), MAX_ROWS)
        val written = entries.count { it.kind != GroupLogEntry.BOT_KIND }

        // a closed day gains no messages, so one that was too thin is not read again on the next pass
        attempts[key] = if (written < MIN_DAY_MESSAGES) MAX_ATTEMPTS else (attempts[key] ?: 0) + 1

        if (written < MIN_DAY_MESSAGES) return

        val transcript = renderGroupLog(entries, zone, MAX_LINE_CHARS, SOURCE_CHARS).text
        val earlier = repository.since(chat, day.minusDays(KEPT_DAYS)).takeLast(EARLIER_ENTRIES)
        val content = writer.write(day, transcript, earlier) ?: return

        repository.store(chat, DiaryEntry(day, content))

        log.info { "diary entry written: chat=[$chat] day=[$day] messages=[$written] chars=[${content.length}]" }
    }

    private fun LocalDate.startOfDay(): Instant = atStartOfDay(zone).toInstant()

    private companion object {
        val PASS_INTERVAL = 15.minutes

        // a day of a few stray lines is not one to remember, and an entry about it would be padding
        const val MIN_DAY_MESSAGES = 15

        const val MAX_ATTEMPTS = 3

        // what one day's transcript may cost the writer's prompt, the same bound a digest has
        const val MAX_ROWS = 1_500
        const val MAX_LINE_CHARS = 300
        const val SOURCE_CHARS = 12_000

        // every group turn carries the shown entries, so they are few; the kept ones are what a chat
        // that went quiet for a few days still comes back to
        const val SHOWN_ENTRIES = 3
        const val EARLIER_ENTRIES = 2
        const val KEPT_DAYS = 7L

        val log = KotlinLogging.logger {}
    }
}

// an entry was written from what people said in the chat, so it is quoted text like the transcript
// it came from, and gets the same defusing before it is shown inside a prompt block.
internal fun renderDiary(entries: List<DiaryEntry>): String =
    entries.joinToString("\n\n") { "${it.day}: ${it.content.neutralizePromptBlocks()}" }
