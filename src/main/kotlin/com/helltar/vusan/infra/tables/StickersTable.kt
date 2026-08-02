package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// one row per known sticker, shared by every chat. `file_unique_id` is stable across bots and time,
// so it is the identity; `file_id` is only a handle for this bot and is refreshed with the set.
object StickersTable : LongIdTable("stickers") {

    val fileUniqueId = varchar("file_unique_id", 128).uniqueIndex()
    val fileId = text("file_id")
    val setName = varchar("set_name", 64).index()
    val emoji = varchar("emoji", 32).nullable()

    // animated and video stickers cannot be handed to vision directly, so the still thumbnail is what
    // gets described. it is absent on some stickers, and then the sticker file itself is used.
    val thumbnailFileId = text("thumbnail_file_id").nullable()

    // null until vision has looked at the sticker; an undescribed sticker never enters the index.
    val description = text("description").nullable()

    // people send stickers vision will not describe — explicit ones it refuses, and ones whose
    // download or call simply fails. without this counter those rows stay at the head of the
    // description queue forever and starve every sticker behind them.
    val describeAttempts = integer("describe_attempts").default(0)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}
