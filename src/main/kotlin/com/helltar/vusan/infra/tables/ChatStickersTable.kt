package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// which individual stickers a chat actually uses. `file_unique_id` is recorded before a set is
// learned, then joins the usage back to the shared sticker row once vision has described it.
object ChatStickersTable : LongIdTable("chat_stickers") {

    val chatId = long("chat_id")
    val fileUniqueId = varchar("file_unique_id", 128)
    val seenCount = integer("seen_count").default(0)
    val lastSeenAt = timestamp("last_seen_at").clientDefault { Instant.now() }

    init {
        uniqueIndex(chatId, fileUniqueId)
        index(false, chatId, lastSeenAt)
    }
}
