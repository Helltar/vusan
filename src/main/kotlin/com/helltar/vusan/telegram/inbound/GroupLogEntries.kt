package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.telegram.telegramChat

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.telegram.SentPoll
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.polls.PollAnswer
import java.time.Instant

// what one message is allowed to cost the log. layout whitespace is noise in a transcript, so the
// text is collapsed rather than kept as written.
private const val MAX_TEXT_CHARS = 2_000

// what a vote reads as in the transcript, beside the "text", "photo" and so on of real messages.
private const val POLL_ANSWER_KIND = "poll answer"

// a forwarded channel post can be four thousand characters of somebody else's writing, and a day of
// those is what makes a group's transcript unreadable. the origin plus the opening is enough to
// recall what was shared.
private const val MAX_FORWARDED_TEXT_CHARS = 1_000

/**
 * Turns an inbound message into its chat-log row, or `null` when there is nothing worth recording.
 * Service messages (joins, leaves, pins) carry neither text nor media and are dropped here.
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
        messageId = messageIdLong,
        kind = kind,
        // telegram's own send time: a digest keyed on local days must not drift when the bot is
        // catching up on a backlog of updates.
        sentAt = date?.let { Instant.ofEpochSecond(it.toLong()) } ?: Instant.now(),
        threadId = messageThreadId?.toLong(),
        senderId = senderIdOrNull()?.toString(),
        senderUsername = senderUsernameOrNull(),
        senderName = senderDisplayNameOrNull(),
        text = text,
        descriptor = descriptor,
        forwardFrom = forwardFrom,
        replyToMessageId = replyToMessageIdOrNull()
    )
}

/**
 * A vote on one of the bot's polls, as a line of the chat transcript.
 *
 * `poll_answer` is not a message and has no place of its own in the chat, so the answer is recorded
 * the way a person's message is — the transcript is where the agent reads what happened, and a quiz
 * nobody's answers reach is a question the bot asked and never heard back from.
 *
 * `null` when there is nothing to record: an anonymous vote names no one, and a retracted one
 * ([PollAnswer.optionIds] empty) is the absence of an answer rather than an answer.
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
        // an answer is not a message, so it has no id of its own and nothing can reply to it.
        messageId = null,
        kind = POLL_ANSWER_KIND,
        sentAt = Instant.now(),
        senderId = voter.id.toString(),
        senderUsername = voter.userName,
        senderName = displayName(voter.firstName, voter.lastName),
        text = "answered: ${chosen.joinToString(", ")}$verdict".collapseWhitespaceAndCap(MAX_TEXT_CHARS)
    )
}
