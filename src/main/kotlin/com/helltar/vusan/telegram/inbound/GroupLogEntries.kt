package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.common.collapseWhitespaceAndCap
import org.telegram.telegrambots.meta.api.objects.message.Message
import java.time.Instant

// what one message is allowed to cost the log. layout whitespace is noise in a transcript, so the
// text is collapsed rather than kept as written.
private const val MAX_TEXT_CHARS = 2_000

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
        chatId = chatIdLong,
        messageId = messageIdLong,
        kind = kind,
        // telegram's own send time: a digest keyed on local days must not drift when the bot is
        // catching up on a backlog of updates.
        sentAt = date?.let { Instant.ofEpochSecond(it.toLong()) } ?: Instant.now(),
        threadId = messageThreadId?.toLong(),
        senderId = senderIdOrNull(),
        senderUsername = senderUsernameOrNull(),
        senderName = senderDisplayNameOrNull(),
        text = text,
        descriptor = descriptor,
        forwardFrom = forwardFrom,
        replyToMessageId = replyToMessageIdOrNull()
    )
}
