package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// which sticker sets a chat uses, and how recently. the catalog is global but the index is per chat, so a
// group sees only the stickers its own people use.
// telegram-owned; `chat_id` has no `platform`, as in TelegramChatStickersTable.
object TelegramChatStickerSetsTable : Table("telegram_chat_sticker_sets") {

    val chatId = long("chat_id")
    val setName = varchar("set_name", 64)
    val seenCount = integer("seen_count").default(0)
    val lastSeenAt = timestamp("last_seen_at").clientDefault { Instant.now() }

    // when this chat caused the set to be learned, the only moment it costs anything; null for a set known
    // from elsewhere or not yet earned.
    val learnedAt = timestamp("learned_at").nullable()

    override val primaryKey = PrimaryKey(chatId, setName)
}
