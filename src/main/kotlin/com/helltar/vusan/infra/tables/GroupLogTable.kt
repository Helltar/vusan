package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

// every message seen in a group, including the ones never addressed to the bot. this is the only
// record of what a group actually talked about: `conversation_messages` is keyed by user and chat, and
// holds just the turns the bot took part in.
object GroupLogTable : LongIdTable("group_log") {

    val chatId = long("chat_id")

    // null for the bot's own messages: delivery does not carry the sent message id back.
    val messageId = long("message_id").nullable()

    val threadId = long("thread_id").nullable()

    // null for anonymous admins and for posts forwarded in by a linked channel.
    val senderId = long("sender_id").nullable()

    val senderUsername = varchar("sender_username", 64).nullable()
    val senderName = varchar("sender_name", 200).nullable()
    val kind = varchar("kind", 24)
    val text = text("text").nullable()

    // short human label for non-text content ("😂 HotCat", "0:14", "report.pdf"), never file ids.
    val descriptor = varchar("descriptor", 200).nullable()

    val forwardFrom = varchar("forward_from", 128).nullable()
    val replyToMessageId = long("reply_to_message_id").nullable()

    // telegram's own send time, not the moment the row was written: a digest keyed on local days
    // must not drift when the bot lags behind the update stream.
    val sentAt = timestamp("sent_at")

    init {
        index(false, chatId, sentAt)

        // an update redelivered after a crash must not duplicate a row. SQLite treats NULLs as
        // distinct here, so the bot's own rows never collide with each other.
        uniqueIndex(chatId, messageId)
    }
}
