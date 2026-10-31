package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.agent.neutralizePromptBlocks
import com.helltar.vusan.common.limitTo
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val ANONYMOUS_LABEL = "anon"
private const val BOT_LABEL = "bot"

// kinds whose whole content is the text, so a `[kind]` marker says nothing.
private val TEXTUAL_KINDS = setOf("text", "rich message")

private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_AND_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** [text] holds the [includedCount] newest entries that fit its budget. */
internal data class RenderedGroupLog(val text: String, val includedCount: Int)

/**
 * Drops the exchanges [userId] already had with the bot: their answered messages and the bot's answers.
 *
 * Those turns are replayed as real `user`/`assistant` roles from that user's history, so keeping them would
 * show the exchange twice. Everyone else's messages stay, with the bot's replies to them, which are in nobody
 * else's history.
 *
 * The link is the reply anchor: in a group the bot answers by replying. An unanchored reply (a scheduled
 * task, a rejected anchor) has no link and stays, as nothing proves it a duplicate.
 */
internal fun List<GroupLogEntry>.withoutExchangesWith(userId: String): List<GroupLogEntry> {
    val ownMessageIds = mapNotNullTo(mutableSetOf()) { it.messageId.takeIf { _ -> it.senderId == userId } }

    val answeredIds =
        filter { it.kind == GroupLogEntry.BOT_KIND }
            .mapNotNullTo(mutableSetOf()) { it.replyToMessageId?.takeIf(ownMessageIds::contains) }

    if (answeredIds.isEmpty()) return this

    return filterNot { entry ->
        when (entry.kind) {
            GroupLogEntry.BOT_KIND -> entry.replyToMessageId in answeredIds
            else -> entry.messageId in answeredIds
        }
    }
}

/**
 * Renders entries as a transcript, oldest first. If it fits [budgetChars] as written, everything is quoted in
 * full; otherwise each message is cut to [maxTextChars] and lines are taken from the newest end, since recaps
 * ask how a conversation ended far more than how it began. [RenderedGroupLog.includedCount] says how much got
 * in.
 */
internal fun renderGroupLog(
    entries: List<GroupLogEntry>,
    zone: ZoneId,
    maxTextChars: Int,
    budgetChars: Int,
): RenderedGroupLog {
    val spansDays = entries.mapTo(mutableSetOf()) { LocalDate.ofInstant(it.sentAt, zone) }.size > 1
    val formatter = if (spansDays) DATE_AND_TIME else TIME_ONLY

    // the cap keeps one long message from crowding out a busy window; a narrow window or one person's
    // messages is asked about for what was said, and cutting there leaves the model retelling a story from
    // its opening only.
    renderWholeOrNull(entries, zone, formatter, budgetChars)?.let { return it }

    val lines = ArrayDeque<String>()
    var used = 0

    for (entry in entries.asReversed()) {
        val line = entry.toLine(zone, formatter, maxTextChars)
        val cost = line.length + 1

        if (lines.isNotEmpty() && used + cost > budgetChars) break

        lines.addFirst(line)
        used += cost
    }

    return RenderedGroupLog(lines.joinToString("\n"), lines.size)
}

private fun renderWholeOrNull(
    entries: List<GroupLogEntry>,
    zone: ZoneId,
    formatter: DateTimeFormatter,
    budgetChars: Int,
): RenderedGroupLog? {
    val lines = ArrayList<String>(entries.size)
    var used = 0

    for (entry in entries) {
        val line = entry.toLine(zone, formatter, maxTextChars = Int.MAX_VALUE)
        used += line.length + 1

        if (used > budgetChars) return null

        lines += line
    }

    return RenderedGroupLog(lines.joinToString("\n"), lines.size)
}

// everything but the clock is user text (names, forwarded channels, the message), shown inside
// `<recent_chat>` ahead of the request; neutralizing the line keeps one member from opening a prompt block in
// someone else's turn.
private fun GroupLogEntry.toLine(zone: ZoneId, formatter: DateTimeFormatter, maxTextChars: Int): String =
    buildString {
        append(formatter.format(ZonedDateTime.ofInstant(sentAt, zone)))
        append(' ')
        append(authorLabel())
        forwardFrom?.let { append(" [forward from $it]") }
        mediaMarker()?.let { append(" $it") }
        text?.let { append(": ${it.limitTo(maxTextChars)}") }
    }.neutralizePromptBlocks()

private fun GroupLogEntry.authorLabel(): String =
    if (kind == GroupLogEntry.BOT_KIND) BOT_LABEL else senderUsername ?: senderName ?: ANONYMOUS_LABEL

private fun GroupLogEntry.mediaMarker(): String? =

    when {
        kind in TEXTUAL_KINDS -> null
        // a bot row names its content in the descriptor; a `bot` prefix would stutter.
        kind == GroupLogEntry.BOT_KIND -> descriptor?.let { "[$it]" }
        else -> "[${listOfNotNull(kind, descriptor).joinToString(" ")}]"
    }
