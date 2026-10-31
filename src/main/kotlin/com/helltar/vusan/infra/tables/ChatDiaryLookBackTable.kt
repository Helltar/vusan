package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

// the bot's look-back over one closed week or month of a group, written once from that span's chat_diary
// entries and cleared with the chat's transcript (see GroupLogRepository).
object ChatDiaryLookBackTable : Table("chat_diary_look_back") {

    val platform = platformColumn()
    val chatId = externalId("chat_id")

    // `week` (monday to sunday) or `month`, a DiaryPeriod name.
    val period = varchar("period", 8)

    // the span's first local day as `yyyy-MM-dd` in the bot's zone, as chat_diary names days.
    val firstDay = varchar("first_day", 10)

    val content = text("content")

    override val primaryKey = PrimaryKey(platform, chatId, period, firstDay)
}
