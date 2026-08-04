package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.common.limitTo
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val ANONYMOUS_LABEL = "anon"
private const val BOT_LABEL = "bot"

// kinds whose whole content is the text itself, so a `[kind]` marker would say nothing.
private val TEXTUAL_KINDS = setOf("text", "rich message")

private val TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_AND_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** [text] holds the [includedCount] newest entries that fit the budget it was rendered under. */
internal data class RenderedGroupLog(val text: String, val includedCount: Int)

/**
 * Drops the exchanges [userId] already had with the bot: their messages that the bot answered, and
 * the bot's answers to them.
 *
 * Those turns are replayed to the model as real `user`/`assistant` roles from that user's own
 * history, so leaving them here would show the same exchange twice — once as the conversation, once
 * as something overheard. Everyone else's messages stay, including the bot's replies to them, which
 * are in nobody else's history.
 *
 * The link is the reply anchor: in a group the bot answers by replying, so its row points at the
 * message it answered. An unanchored reply (a scheduled task, or a reply whose anchor Telegram
 * rejected) keeps no link and is left in place, because there is nothing to prove it duplicates.
 */
internal fun List<GroupLogEntry>.withoutExchangesWith(userId: Long): List<GroupLogEntry> {
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
 * Renders entries as a transcript, oldest line first. Lines are taken from the newest end until
 * [budgetChars] runs out, because a recap is asked about how a conversation ended far more often
 * than about how it started; [RenderedGroupLog.includedCount] tells the caller how much got in.
 */
internal fun renderGroupLog(
    entries: List<GroupLogEntry>,
    zone: ZoneId,
    maxTextChars: Int,
    budgetChars: Int
): RenderedGroupLog {
    val spansDays = entries.mapTo(mutableSetOf()) { LocalDate.ofInstant(it.sentAt, zone) }.size > 1
    val formatter = if (spansDays) DATE_AND_TIME else TIME_ONLY
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

private fun GroupLogEntry.toLine(zone: ZoneId, formatter: DateTimeFormatter, maxTextChars: Int): String =
    buildString {
        append(formatter.format(ZonedDateTime.ofInstant(sentAt, zone)))
        append(' ')
        append(authorLabel())
        forwardFrom?.let { append(" [forward from $it]") }
        mediaMarker()?.let { append(" $it") }
        text?.let { append(": ${it.limitTo(maxTextChars)}") }
    }

private fun GroupLogEntry.authorLabel(): String =
    if (kind == GroupLogEntry.BOT_KIND) BOT_LABEL else senderUsername ?: senderName ?: ANONYMOUS_LABEL

private fun GroupLogEntry.mediaMarker(): String? =
    when {
        kind in TEXTUAL_KINDS -> null
        // a bot row names its own content in the descriptor, so prefixing it with `bot` would stutter.
        kind == GroupLogEntry.BOT_KIND -> descriptor?.let { "[$it]" }
        else -> "[${listOfNotNull(kind, descriptor).joinToString(" ")}]"
    }
