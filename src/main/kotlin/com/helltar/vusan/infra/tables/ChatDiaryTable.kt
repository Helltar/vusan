package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

// the bot's own entry about one closed local day of a group, written once from that day's transcript.
// it belongs to the chat and goes when the chat's transcript is cleared — see GroupLogRepository.
object ChatDiaryTable : Table("chat_diary") {

    val platform = platformColumn()
    val chatId = externalId("chat_id")

    // local date as `yyyy-MM-dd`, in the bot's own zone, the one the group log cuts its days on.
    val day = varchar("day", 10)

    val content = text("content")

    override val primaryKey = PrimaryKey(platform, chatId, day)
}
