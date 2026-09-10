package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// which individual stickers a chat actually uses. `file_unique_id` is recorded before a set is
// learned, then joins the usage back to the shared sticker row once vision has described it.
//
// Telegram-owned like the rest of the sticker tables, so `chat_id` carries no `platform` beside it: a
// sticker is reached by `file_id` and lives only in `telegram/tools/sticker/`. What keeps two messengers
// out of one row here is that no other one can write it, not a column.
object ChatStickersTable : Table("chat_stickers") {

    val chatId = long("chat_id")
    val fileUniqueId = varchar("file_unique_id", 128)
    val seenCount = integer("seen_count").default(0)
    val lastSeenAt = timestamp("last_seen_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(chatId, fileUniqueId)

    init {
        index(false, chatId, lastSeenAt)
    }
}
