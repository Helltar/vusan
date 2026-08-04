package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.common.rethrowIfCancellation
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
 * A window that fits is quoted verbatim and costs nothing beyond the query. A window that does not
 * and reaches back into a closed day is split by local day: every **closed** day is replaced by a
 * cached one-off recap, and the current day stays verbatim. Only closed days are cached — today is
 * still being written to, so its recap would be stale the moment it was stored. A window with no
 * closed day in it has nothing to summarize, and is truncated to the newest entries instead.
 */
class GroupLogReader(
    private val repository: GroupLogRepository,
    private val digester: GroupLogDigester?,
    private val budgetChars: Int,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    private companion object {
        const val MAX_LINE_TEXT_CHARS = 300

        // a plausible floor for one rendered line, used to turn the character budget into a row
        // limit so a month-wide window is never pulled out of SQLite whole.
        const val MIN_LINE_COST = 40
        const val MAX_ROWS = 1_000
        const val MAX_ROWS_PER_DAY = 1_500

        // what one day's transcript may cost the digester's own prompt.
        const val DIGEST_SOURCE_CHARS = 12_000

        // share of the budget kept for quoting today, the part most questions are actually about.
        const val TODAY_BUDGET_SHARE = 0.4

        const val COUNT_FROM_HEADER = "Answer how many and how often from the count above, never by counting what is quoted."

        val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val log = KotlinLogging.logger {}
    }

    suspend fun read(
        chatId: Long,
        window: Duration,
        author: String? = null,
        now: Instant = Instant.now()
    ): String {
        val from = now.minusSeconds(window.inWholeSeconds)
        val total = repository.countInWindow(chatId, from, now, author)

        if (total == 0L) return emptyResult(author, from, now)

        val rowLimit = (budgetChars / MIN_LINE_COST).coerceIn(1, MAX_ROWS)
        val entries = repository.readWindow(chatId, from, now, limit = rowLimit, author = author)
        val rendered = renderGroupLog(entries, zone, MAX_LINE_TEXT_CHARS, budgetChars)

        if (rendered.includedCount.toLong() >= total) {
            return header(from, now, total, author) + "\n\n" + block("transcript", rendered.text)
        }

        // a digest is whole-chat by construction, so it cannot answer "what did this one person say".
        // a window lying inside today has no closed day to summarize either, and the day split would
        // only widen it to the whole day to announce that: truncating is the honest answer there.
        if (author != null || digester == null || !reachesAClosedDay(from, now)) {
            return truncatedResult(rendered, from, now, total, author)
        }

        return digestedResult(chatId, from, now)
    }

    private fun reachesAClosedDay(from: Instant, now: Instant): Boolean =
        LocalDate.ofInstant(from, zone) < LocalDate.ofInstant(now, zone)

    private suspend fun digestedResult(chatId: Long, from: Instant, now: Instant): String {
        val today = LocalDate.ofInstant(now, zone)

        // snapping to a day boundary makes every past day in the window whole, which is what makes
        // its recap cacheable under a plain date key.
        val firstDay = LocalDate.ofInstant(from, zone)
        val windowStart = firstDay.startOfDay()
        val closedDays = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { it < today }.toList()

        // snapping widened the window, so the caller's count over the narrower one would contradict
        // the window this header prints.
        val total = repository.countInWindow(chatId, windowStart, now, author = null)

        val todayStart = today.startOfDay()
        val todayTotal = repository.countInWindow(chatId, todayStart, now, author = null)

        val todayBudget = (budgetChars * TODAY_BUDGET_SHARE).toInt()
        val todayEntries = repository.readWindow(chatId, todayStart, now, limit = MAX_ROWS, author = null)
        val todayRendered = renderGroupLog(todayEntries, zone, MAX_LINE_TEXT_CHARS, todayBudget)

        val digestBudget = budgetChars - todayRendered.text.length
        val digests = collectDigests(chatId, closedDays, digestBudget)

        return buildString {
            append(header(windowStart, now, total, author = null))
            appendLine()
            append("Too much to quote in full: closed days are summarized, today is quoted. ")
            append(COUNT_FROM_HEADER)

            if (digests.size < closedDays.size) {
                appendLine()
                append("The oldest ${closedDays.size - digests.size} day(s) of the window did not fit and are omitted.")
            }

            digests.forEach {
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
                        closingTag = "today"
                    )
                )
            }
        }
    }

    // newest days first so the ones that survive a tight budget are the recent ones, then flipped
    // back into reading order.
    private suspend fun collectDigests(chatId: Long, closedDays: List<LocalDate>, budget: Int): List<String> {
        val blocks = ArrayDeque<String>()
        var used = 0

        for (day in closedDays.asReversed()) {
            val digest = digestOf(chatId, day) ?: continue
            val rendered = block("day date=\"$day\"", digest, closingTag = "day")

            if (blocks.isNotEmpty() && used + rendered.length > budget) break

            blocks.addFirst(rendered)
            used += rendered.length
        }

        return blocks.toList()
    }

    private suspend fun digestOf(chatId: Long, day: LocalDate): String? {
        repository.digestFor(chatId, day)?.let { return it }

        val compactor = digester ?: return null
        val dayEnd = day.plusDays(1).startOfDay().minusMillis(1)
        val entries = repository.readWindow(chatId, day.startOfDay(), dayEnd, limit = MAX_ROWS_PER_DAY)

        if (entries.isEmpty()) return null

        val source = renderGroupLog(entries, zone, MAX_LINE_TEXT_CHARS, DIGEST_SOURCE_CHARS)

        val digest =
            try {
                compactor.digest(day, source.text)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn(e) { "chat log digest failed for chat=$chatId day=$day" }
                null
            } ?: return null

        runCatching { repository.storeDigest(chatId, day, entries.size, digest) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to cache chat log digest for chat=$chatId day=$day" }
            }

        return digest
    }

    private fun truncatedResult(
        rendered: RenderedGroupLog,
        from: Instant,
        now: Instant,
        total: Long,
        author: String?
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

    private fun emptyResult(author: String?, from: Instant, now: Instant): String =
        author
            ?.let { "No messages from `$it` between ${stamp(from)} and ${stamp(now)}." }
            ?: "No messages recorded between ${stamp(from)} and ${stamp(now)}."

    // the count spans the whole window while the transcript under it may be a subset, so the number
    // is labelled as the exact one. without that a "how many" answer becomes a tally of visible lines.
    private fun header(from: Instant, now: Instant, total: Long, author: String?): String =
        buildString {
            append("Chat log for this group.")
            appendLine()
            append("Window: ${stamp(from)} .. ${stamp(now)} ${zone.id}.")
            appendLine()
            append("Messages in this window: $total")
            author?.let { append(" from `$it`") }
            append(" (exact, for the whole window).")
        }

    private fun stamp(instant: Instant): String =
        TIMESTAMP.format(ZonedDateTime.ofInstant(instant, zone))

    private fun LocalDate.startOfDay(): Instant = atStartOfDay(zone).toInstant()
}

private fun block(openTag: String, content: String, closingTag: String = openTag): String =
    "<$openTag>\n${content.trim()}\n</$closingTag>"
