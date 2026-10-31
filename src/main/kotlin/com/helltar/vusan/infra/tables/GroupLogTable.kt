package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

// every message seen in a group, addressed to the bot or not: the only record of what a group talked about,
// since `conversation_messages` is keyed by user and chat and holds only the bot's turns.
object GroupLogTable : LongIdTable("group_log") {

    val platform = platformColumn()
    val chatId = externalId("chat_id")

    // null for the bot's own messages: delivery does not return the sent id.
    val messageId = externalId("message_id").nullable()

    // null for anonymous admins and linked-channel posts.
    val senderId = externalId("sender_id").nullable()

    val senderUsername = varchar("sender_username", 64).nullable()
    val senderName = varchar("sender_name", 200).nullable()
    val kind = varchar("kind", 24)
    val text = text("text").nullable()

    // a short human label for non-text content ("😂 HotCat", "0:14", "report.pdf"), never file ids. the widths
    // mirror GroupLogEntry.MAX_DESCRIPTOR_CHARS and MAX_FORWARD_FROM_CHARS, which every writer caps to
    val descriptor = varchar("descriptor", 200).nullable()

    val forwardFrom = varchar("forward_from", 128).nullable()
    val replyToMessageId = externalId("reply_to_message_id").nullable()

    // telegram's send time, not the write time, so diary days do not drift while the bot lags behind the
    // stream.
    val sentAt = timestamp("sent_at")

    init {
        index(false, platform, chatId, sentAt)

        // a redelivered update must not duplicate a row. SQLite treats NULLs as distinct here, so the bot's
        // own rows never collide.
        uniqueIndex(platform, chatId, messageId)
    }
}
