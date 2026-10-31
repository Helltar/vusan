package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// the learned sticker sets, with when each was last checked against telegram: a `file_id` is only a handle
// and owners edit or delete sets, so what was learned is re-read now and then.
object TelegramStickerSetsTable : Table("telegram_sticker_sets") {

    val name = varchar("name", 64)
    val refreshedAt = timestamp("refreshed_at").clientDefault { Instant.now() }

    // the telegram-issued name is the set's only identity.
    override val primaryKey = PrimaryKey(name)
}
