package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// the sticker sets pulled in so far, with the last time their contents were checked against Telegram.
// a `file_id` is only a handle and a set can be edited or deleted by its owner, so what was learned
// has to be re-read now and then rather than trusted forever.
object StickerSetsTable : LongIdTable("sticker_sets") {

    val name = varchar("name", 64).uniqueIndex()
    val refreshedAt = timestamp("refreshed_at").clientDefault { Instant.now() }
}
