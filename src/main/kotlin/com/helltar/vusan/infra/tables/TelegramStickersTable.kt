package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

// one row per known sticker, shared by every chat. `file_unique_id` is stable across bots and time, so it is
// the identity; `file_id` is a per-bot handle refreshed with the set.
object TelegramStickersTable : LongIdTable("telegram_stickers") {

    val fileUniqueId = varchar("file_unique_id", 128).uniqueIndex()
    val fileId = text("file_id")
    val setName = varchar("set_name", 64).index()
    val emoji = varchar("emoji", 32).nullable()

    // animated and video stickers cannot go to vision directly, so the still thumbnail is described, or the
    // sticker file when there is none.
    val thumbnailFileId = text("thumbnail_file_id").nullable()

    // null until vision described the sticker; undescribed stickers never enter the index.
    val description = text("description").nullable()

    // vision refuses explicit stickers and downloads or calls fail; without this counter those rows would sit
    // at the head of the queue and starve the rest.
    val describeAttempts = integer("describe_attempts").default(0)
}
