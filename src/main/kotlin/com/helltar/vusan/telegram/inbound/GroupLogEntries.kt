package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.telegram.telegramChat

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.telegram.SentPoll
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.polls.PollAnswer
import java.time.Instant

// layout whitespace is noise in a transcript, so text is collapsed
private const val MAX_TEXT_CHARS = GroupLogEntry.MAX_TEXT_CHARS

// how a vote reads in the transcript, beside "text", "photo" and so on.
private const val POLL_ANSWER_KIND = "poll answer"

// a forwarded channel post can be thousands of characters of someone else's writing; the origin plus the
// opening is enough to recall what was shared.
private const val MAX_FORWARDED_TEXT_CHARS = 1_000

/**
 * An inbound message as its chat-log row, or `null` when there is nothing to record. Service messages (joins,
 * leaves, pins) carry neither text nor media and are dropped.
 */
internal fun Message.toGroupLogEntry(): GroupLogEntry? {
    val forwardFrom = forwardOriginLabel()
    val kind = contentTypeName()
    val descriptor = groupLogDescriptor()

    val text =
        textSnippetOrNull()
            ?.collapseWhitespaceAndCap(if (forwardFrom == null) MAX_TEXT_CHARS else MAX_FORWARDED_TEXT_CHARS)

    if (text == null && descriptor == null && kind == "unknown") return null

    return GroupLogEntry(
        chat = telegramChat(chatIdLong),
        messageId = messageIdLong.toString(),
        kind = kind,
        // telegram's send time, so diary days do not drift while the bot catches up on a backlog.
        sentAt = date?.let { Instant.ofEpochSecond(it.toLong()) } ?: Instant.now(),
        senderId = senderIdOrNull()?.toString(),
        senderUsername = senderUsernameOrNull(),
        senderName = senderDisplayNameOrNull(),
        text = text,
        descriptor = descriptor,
        forwardFrom = forwardFrom,
        replyToMessageId = replyToMessageIdOrNull()?.toString(),
    )
}

/**
 * A vote on one of the bot's polls as a transcript line. `poll_answer` is not a message, but the transcript
 * is where the agent reads what happened, and a quiz whose answers never reach it is a question never heard
 * back from.
 *
 * `null` when there is nothing to record: an anonymous vote names no one, and a retracted one
 * ([PollAnswer.optionIds] empty) is no answer.
 */
internal fun PollAnswer.toGroupLogEntry(poll: SentPoll): GroupLogEntry? {
    val voter = user ?: return null
    val chosen = optionIds.orEmpty().mapNotNull { poll.options.getOrNull(it) }.ifEmpty { return null }

    val verdict =
        poll.correctOptionIndex?.let { correct ->
            if (optionIds.orEmpty().singleOrNull() == correct) " (correct)" else " (wrong)"
        }.orEmpty()

    return GroupLogEntry(
        chat = telegramChat(poll.chatId),
        // an answer is not a message: it has no id and nothing can reply to it.
        messageId = null,
        kind = POLL_ANSWER_KIND,
        sentAt = Instant.now(),
        senderId = voter.id.toString(),
        senderUsername = voter.userName,
        senderName = displayName(voter.firstName, voter.lastName),
        text = "answered: ${chosen.joinToString(", ")}$verdict".collapseWhitespaceAndCap(MAX_TEXT_CHARS),
    )
}
