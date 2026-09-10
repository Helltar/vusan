package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

// what is known about one person's conversation in one chat besides the messages themselves, keyed
// exactly like `conversation_messages` and one row for both: a conversation is cleared and compacted
// as one thing, and two tables holding the same key meant two writes and two chances to disagree.
object ConversationsTable : Table("conversations") {

    val platform = platformColumn()
    val userId = externalId("user_id")
    val chatId = externalId("chat_id")

    // how many times this conversation has been wiped. anything holding on to one across turns — an
    // unanswered inline choice, a parked attachment — carries the revision it was made under, so a
    // button pressed after a `/clear` answers a conversation that no longer exists.
    val revision = long("revision").default(0)

    // the recap standing in for the turns compacted away; null until there is one, and again after a
    // clear. a clear keeps the revision: it counts wipes, and it must never go backwards.
    val summary = text("summary").nullable()

    // the last message the summary covers, `0` when there is none. a watermark rather than a foreign
    // key: the message it names is pruned away once the summary stands in for it.
    val summarizedThroughMessageId = long("summarized_through_message_id").default(0)

    override val primaryKey = PrimaryKey(platform, userId, chatId)
}
