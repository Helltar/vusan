package com.helltar.vusan.tools.diary

import com.helltar.vusan.agent.presence.DiaryEntry
import com.helltar.vusan.agent.presence.DiaryLookBack
import com.helltar.vusan.agent.presence.DiaryPeriod
import com.helltar.vusan.agent.presence.DiaryRepository
import com.helltar.vusan.agent.presence.label
import com.helltar.vusan.agent.presence.renderDiary
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private const val MAX_CONTAINS_CHARS = 100

// the blank line between two rendered entries
private const val SEPARATOR_CHARS = 2

/**
 * The bot's diary about a group, read on demand: what a turn's `<diary>` block leaves out, the days
 * past its last week and the weeks and months before the newest ones, outliving the transcript itself.
 */
class DiaryTools(
    private val diary: DiaryRepository,
    private val context: RequestContext,
    private val budgetChars: Int,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
) : ToolSet {

    @Tool(DiaryToolDescriptions.READ_DIARY, readOnly = true)
    suspend fun readDiary(
        @Arg(DiaryToolDescriptions.READ_DIARY_FROM) from: String? = null,
        @Arg(DiaryToolDescriptions.READ_DIARY_TO) to: String? = null,
        @Arg(DiaryToolDescriptions.READ_DIARY_CONTAINS) contains: String? = null,
    ): String = suspendToolGuard {
        if (context.chat.isPrivate)
            return@suspendToolGuard "No diary in a private chat — this conversation is already your history."

        val chat = context.chatRef
        val named = from?.let { daysNamedBy(it, "from") }
        val first = named?.start
        val last = to?.let { daysNamedBy(it, "to").endInclusive } ?: named?.endInclusive
        val needle = contains?.requireToolText("contains", MAX_CONTAINS_CHARS)

        require(first == null || last == null || first <= last) { "`from` must not be after `to`" }

        val opening =
            diary.firstDay(chat)
                ?: return@suspendToolGuard "Your diary about this group is empty: no day here has been written up yet."

        val range = (first ?: opening)..(last ?: LocalDate.ofInstant(clock(), zone))
        val described = "from ${range.start} to ${range.endInclusive}" + needle?.let { ", mentioning `$it`" }.orEmpty()

        val entries = diary.between(chat, range.start, range.endInclusive).filter { it.content.mentions(needle) }
        val lookBacks = diary.lookBacksBetween(chat, range.start, range.endInclusive).filter { it.content.mentions(needle) }

        if (entries.isEmpty() && lookBacks.isEmpty())
            return@suspendToolGuard "Nothing in your diary about this group $described. It begins on $opening."

        val fitted = fitDiary(entries, lookBacks, budgetChars)

        buildString {
            append("Your diary about this group, $described: ${lookBacks.size} look-back(s) and ${entries.size} daily entries.")

            if (fitted.entries.size < entries.size || fitted.lookBacks.size < lookBacks.size) {
                append(
                    " Not all of it fits: shown are ${fitted.lookBacks.size} look-back(s) and the newest " +
                            "${fitted.entries.size} daily entries. Narrow `from` and `to` to read the rest.",
                )
            }

            append("\n\n")
            append(xmlBlock("diary", renderDiary(fitted.entries, fitted.lookBacks)))
            append("\n\n")
            append(
                "This is how you remembered that time when you wrote it down: draw on it as your own memory, " +
                        "and where it disagrees with the chat log or durable memory, they are right.",
            )
        }
    }
}

private fun String.mentions(needle: String?): Boolean = needle == null || contains(needle, ignoreCase = true)

// a day names itself, a month every day in it
private fun daysNamedBy(value: String, label: String): ClosedRange<LocalDate> {
    val text = value.trim()

    runCatching { YearMonth.parse(text) }.getOrNull()?.let { return it.atDay(1)..it.atEndOfMonth() }

    val day =
        requireNotNull(runCatching { LocalDate.parse(text) }.getOrNull()) {
            "`$label` must be a day as `yyyy-MM-dd` or a month as `yyyy-MM`, not `$value`"
        }

    return day..day
}

internal data class FittedDiary(val entries: List<DiaryEntry>, val lookBacks: List<DiaryLookBack>)

// what a read too wide for its budget keeps: the months first and then the weeks, as they hold the most
// per character, then the days, and within each the newest
internal fun fitDiary(entries: List<DiaryEntry>, lookBacks: List<DiaryLookBack>, budgetChars: Int): FittedDiary {
    var left = budgetChars

    fun <T> keepNewest(items: List<T>, cost: (T) -> Int): List<T> {
        val kept = ArrayDeque<T>()

        for (item in items.asReversed()) {
            val itemCost = cost(item) + SEPARATOR_CHARS

            if (itemCost > left) break

            kept.addFirst(item)
            left -= itemCost
        }

        return kept.toList()
    }

    val lookBackCost = { lookBack: DiaryLookBack -> lookBack.span.label().length + 2 + lookBack.content.length }
    val months = keepNewest(lookBacks.filter { it.span.period == DiaryPeriod.MONTH }, lookBackCost)
    val weeks = keepNewest(lookBacks.filter { it.span.period == DiaryPeriod.WEEK }, lookBackCost)
    val days = keepNewest(entries) { it.day.toString().length + 2 + it.content.length }

    return FittedDiary(days, (months + weeks).sortedBy { it.span.firstDay })
}
