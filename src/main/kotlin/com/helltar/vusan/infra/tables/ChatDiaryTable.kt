package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

// the bot's entry about one closed local day of a group, written once from that day's transcript and cleared
// with it (see GroupLogRepository).
object ChatDiaryTable : Table("chat_diary") {

    val platform = platformColumn()
    val chatId = externalId("chat_id")

    // local date as `yyyy-MM-dd` in the bot's zone, the one the group log cuts days on.
    val day = varchar("day", 10)

    val content = text("content")

    override val primaryKey = PrimaryKey(platform, chatId, day)
}
