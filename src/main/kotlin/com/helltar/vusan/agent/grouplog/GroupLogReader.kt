package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.agent.neutralizePromptBlocks
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ChatRef
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration

/**
 * Answers "what happened in this chat" under a fixed character budget.
 *
 * A window that fits is quoted verbatim. One that does not and reaches into a closed day is split by local
 * day: each **closed** day is given as the diary entry already written about it, today stays verbatim.
 * Nothing is generated for the read, so it costs no model call. A window with no closed day is truncated to
 * the newest messages instead.
 *
 * A text search never takes the diary path: an entry keeps what a day was about and drops exactly the one
 * message that named something.
 */
class GroupLogReader(
    private val repository: GroupLogRepository,
    private val diary: RememberedDays?,
    private val budgetChars: Int,
    private val zone: ZoneId = repository.zone,
) {

    suspend fun read(
        chat: ChatRef,
        window: Duration,
        author: String? = null,
        contains: String? = null,
        now: Instant = Instant.now(),
    ): String {
        val from = now.minusSeconds(window.inWholeSeconds)

        contains?.let { return searchResult(chat, from, now, author, it) }

        val total = repository.countInWindow(chat, from, now, author)

        if (total == 0L) return emptyResult(from, now, author)

        val rowLimit = (budgetChars / MIN_LINE_COST).coerceIn(1, MAX_ROWS)
        val entries = repository.readWindow(chat, from, now, limit = rowLimit, author = author)
        val rendered = renderGroupLog(entries, zone, LOG_LINE_CHARS, budgetChars)

        if (rendered.includedCount.toLong() >= total) {
            return header(from, now, total, author) + "\n\n" + block("transcript", rendered.text)
        }

        // a diary entry is about the whole chat, so it cannot answer "what did this person say". a window
        // inside today has no closed day, and the day split would only widen it: truncating is the honest
        // answer.
        if (author != null || diary == null || !reachesAClosedDay(from, now)) {
            return truncatedResult(rendered, from, now, total, author)
        }

        return rememberedResult(chat, from, now, diary)
    }

    private suspend fun searchResult(chat: ChatRef, from: Instant, now: Instant, author: String?, needle: String): String {
        val matches = repository.searchWindow(chat, from, now, needle, author)

        if (matches.isEmpty()) return emptyResult(from, now, author, needle)

        val rendered = renderGroupLog(matches, zone, LOG_LINE_CHARS, budgetChars)

        return buildString {
            append(header(from, now, matches.size.toLong(), author, needle))

            if (rendered.includedCount < matches.size) {
                appendLine()
                append("Showing the newest ${rendered.includedCount} matches; the rest did not fit. ")
                append("$COUNT_FROM_HEADER ")
                append("Narrow the window to read the earlier ones.")
            }

            appendLine()
            appendLine()
            append(block("transcript", rendered.text))
        }
    }

    private fun reachesAClosedDay(from: Instant, now: Instant): Boolean =
        LocalDate.ofInstant(from, zone) < LocalDate.ofInstant(now, zone)

    private suspend fun rememberedResult(chat: ChatRef, from: Instant, now: Instant, diary: RememberedDays): String {
        val today = LocalDate.ofInstant(now, zone)

        // snapping to a day boundary makes every past day whole, so each is told by its entry rather than an
        // entry plus a stray part of its transcript.
        val firstDay = LocalDate.ofInstant(from, zone)
        val windowStart = firstDay.startOfDay()
        val closedDays = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { it < today }.toList()

        // snapping widened the window, so a count over the caller's narrower one would contradict this
        // header.
        val total = repository.countInWindow(chat, windowStart, now, author = null)

        val todayStart = today.startOfDay()
        val todayTotal = repository.countInWindow(chat, todayStart, now, author = null)

        val todayBudget = (budgetChars * TODAY_BUDGET_SHARE).toInt()
        val todayEntries = repository.readWindow(chat, todayStart, now, limit = MAX_ROWS, author = null)
        val todayRendered = renderGroupLog(todayEntries, zone, LOG_LINE_CHARS, todayBudget)

        val written = diaryBetween(chat, diary, firstDay, today.minusDays(1))
        val entries = fitEntries(written, budgetChars - todayRendered.text.length)
        // a day nobody wrote in has nothing to remember
        val unwritten = closedDays.filter { it !in written && hasMessages(chat, it) }

        return buildString {
            append(header(windowStart, now, total))
            appendLine()
            append("Too much to quote in full: today is quoted, and each closed day is given as your diary entry about it — ")
            append("how you remembered the day, not what was said. ")
            append("For exact words, a link, or who wrote something on those days, search with `contains` or read a narrower window. ")
            append(COUNT_FROM_HEADER)

            if (entries.size < written.size) {
                appendLine()
                append("The oldest ${written.size - entries.size} entries of the window did not fit and are omitted.")
            }

            if (unwritten.isNotEmpty()) {
                appendLine()
                append("No diary entry for ${unwritten.joinToString()}.")
            }

            entries.forEach {
                appendLine()
                appendLine()
                append(it)
            }

            if (todayRendered.includedCount > 0) {
                appendLine()
                appendLine()
                append(
                    block(
                        """today date="$today" messages="$todayTotal" quoted="${todayRendered.includedCount}"""",
                        todayRendered.text,
                        closingTag = "today",
                    ),
                )
            }
        }
    }

    // an unreadable diary leaves closed days without entries rather than failing: today is still quoted, and
    // the result says which days have nothing.
    private suspend fun diaryBetween(chat: ChatRef, diary: RememberedDays, from: LocalDate, to: LocalDate): Map<LocalDate, String> =
        runCatching { diary.between(chat, from, to) }
            .getOrElse {
                it.rethrowIfCancellation()
                log.warn(it) { "diary read failed for chat=[$chat] from=[$from] to=[$to]" }
                emptyMap()
            }

    private suspend fun hasMessages(chat: ChatRef, day: LocalDate): Boolean =
        repository.countInWindow(chat, day.startOfDay(), day.plusDays(1).startOfDay().minusMillis(1), author = null) > 0

    // newest first so the recent days survive a tight budget, then back into reading order.
    private fun fitEntries(written: Map<LocalDate, String>, budget: Int): List<String> {
        val blocks = ArrayDeque<String>()
        var used = 0

        for ((day, entry) in written.toSortedMap().entries.reversed()) {
            val rendered = block("day date=\"$day\"", entry.neutralizePromptBlocks(), closingTag = "day")

            if (blocks.isNotEmpty() && used + rendered.length > budget) break

            blocks.addFirst(rendered)
            used += rendered.length
        }

        return blocks.toList()
    }

    private fun truncatedResult(
        rendered: RenderedGroupLog,
        from: Instant,
        now: Instant,
        total: Long,
        author: String?,
    ): String =
        buildString {
            append(header(from, now, total, author))
            appendLine()
            append("Showing the newest ${rendered.includedCount}; the rest of the window did not fit. ")
            append("$COUNT_FROM_HEADER ")
            append("Narrow the window to read the earlier part.")
            appendLine()
            appendLine()
            append(block("transcript", rendered.text))
        }

    private fun emptyResult(from: Instant, now: Instant, author: String?, contains: String? = null): String =
        buildString {
            append("No messages")
            author?.let { append(" from `$it`") }
            contains?.let { append(" mentioning `$it`") }
            if (author == null && contains == null) append(" recorded")
            append(" between ${stamp(from)} and ${stamp(now)}.")
        }

    // the count spans the whole window while the transcript may be a subset, so it is labeled exact;
    // otherwise "how many" becomes a tally of visible lines.
    private fun header(from: Instant, now: Instant, total: Long, author: String? = null, contains: String? = null): String =
        buildString {
            append("Chat log for this group.")
            appendLine()
            append("Window: ${stamp(from)} .. ${stamp(now)} ${zone.id}.")
            appendLine()
            append("Messages in this window: $total")
            author?.let { append(" from `$it`") }
            contains?.let { append(" mentioning `$it`") }
            append(" (exact, for the whole window).")
        }

    private fun stamp(instant: Instant): String =
        TIMESTAMP.format(ZonedDateTime.ofInstant(instant, zone))

    private fun LocalDate.startOfDay(): Instant = atStartOfDay(zone).toInstant()

    private companion object {
        // a floor for one rendered line, turning the character budget into a row limit so a month-wide window
        // never leaves SQLite whole.
        const val MIN_LINE_COST = 40
        const val MAX_ROWS = 1_000

        // share of the budget for quoting today, which most questions are about.
        const val TODAY_BUDGET_SHARE = 0.4

        const val COUNT_FROM_HEADER = "Answer how many and how often from the count above, never by counting what is quoted."

        val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val log = KotlinLogging.logger {}
    }
}

private fun block(openTag: String, content: String, closingTag: String = openTag): String =
    "<$openTag>\n${content.trim()}\n</$closingTag>"

// one line of a rendered transcript, wherever quoted
internal const val LOG_LINE_CHARS = 300

// one local day's transcript for the diary writer, which reads it whole: the rows read and the rendering
// budget. a busy group writes tens of thousands of characters a day and overflow is cut from the morning, so
// it holds such a day whole; it is paid once per closed day, never per turn.
internal const val DAY_SOURCE_ROWS = 3_000
internal const val DAY_SOURCE_CHARS = 100_000

internal suspend fun GroupLogRepository.dayEntries(chat: ChatRef, day: LocalDate, zone: ZoneId): List<GroupLogEntry> {
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)

    return readWindow(chat, start, end, limit = DAY_SOURCE_ROWS)
}

internal fun List<GroupLogEntry>.renderedDay(zone: ZoneId): RenderedGroupLog =
    renderGroupLog(this, zone, LOG_LINE_CHARS, DAY_SOURCE_CHARS)
