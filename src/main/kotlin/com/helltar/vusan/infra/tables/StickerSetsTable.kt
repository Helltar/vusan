package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// the sticker sets pulled in so far, with the last time their contents were checked against Telegram.
// a `file_id` is only a handle and a set can be edited or deleted by its owner, so what was learned
// has to be re-read now and then rather than trusted forever.
object StickerSetsTable : Table("sticker_sets") {

    val name = varchar("name", 64)
    val refreshedAt = timestamp("refreshed_at").clientDefault { Instant.now() }

    // the name Telegram issued is the set: nothing here ever refers to a set by anything else.
    override val primaryKey = PrimaryKey(name)
}
