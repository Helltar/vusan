package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

// what is known about one person's conversation in one chat besides its messages, keyed like
// `conversation_messages`: a conversation is cleared and compacted as one thing, and two tables would mean
// two writes that could disagree.
object ConversationsTable : Table("conversations") {

    val platform = platformColumn()
    val userId = externalId("user_id")
    val chatId = externalId("chat_id")

    // times this conversation was wiped. anything held across turns (an unanswered inline choice, a parked
    // attachment) carries its revision, so a button pressed after `/clear` answers a conversation that no
    // longer exists.
    val revision = long("revision").default(0)

    // the recap standing in for compacted turns; null until there is one and after a clear. a clear keeps the
    // revision, which counts wipes and never goes back.
    val summary = text("summary").nullable()

    // the last message the summary covers, `0` for none. a watermark, not a foreign key: that message is
    // pruned once summarized.
    val summarizedThroughMessageId = long("summarized_through_message_id").default(0)

    override val primaryKey = PrimaryKey(platform, userId, chatId)
}
