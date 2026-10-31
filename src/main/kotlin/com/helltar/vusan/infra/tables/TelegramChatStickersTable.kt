package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// which stickers a chat uses. `file_unique_id` is recorded before a set is learned, then joins the usage to
// the shared sticker row once described.
// telegram-owned like the other sticker tables, so `chat_id` has no `platform`: stickers go by `file_id` and
// live only in `telegram/tools/sticker/`, and no other messenger writes here.
object TelegramChatStickersTable : Table("telegram_chat_stickers") {

    val chatId = long("chat_id")
    val fileUniqueId = varchar("file_unique_id", 128)
    val seenCount = integer("seen_count").default(0)
    val lastSeenAt = timestamp("last_seen_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(chatId, fileUniqueId)

    init {
        index(false, chatId, lastSeenAt)
    }
}
