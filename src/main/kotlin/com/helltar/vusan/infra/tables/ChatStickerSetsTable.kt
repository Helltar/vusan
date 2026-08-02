package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// which sticker sets a chat actually uses, and how recently. the catalog is global but the index
// offered to the model is per chat, so a group is only shown the stickers its own people throw around.
object ChatStickerSetsTable : LongIdTable("chat_sticker_sets") {

    val chatId = long("chat_id")
    val setName = varchar("set_name", 64)
    val seenCount = integer("seen_count").default(0)
    val lastSeenAt = timestamp("last_seen_at").clientDefault { Instant.now() }

    // when this chat caused a set to be pulled in, which is the only moment a set costs anything.
    // null for a set that was already known from elsewhere, or that has not earned its keep yet.
    val learnedAt = timestamp("learned_at").nullable()

    init {
        uniqueIndex(chatId, setName)
    }
}
