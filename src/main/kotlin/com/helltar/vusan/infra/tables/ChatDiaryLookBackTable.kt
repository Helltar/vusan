package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

// the bot's entry looking back over one closed week or month of a group, written once from that span's
// daily entries in chat_diary. it goes with them when the chat's transcript is cleared — see GroupLogRepository.
object ChatDiaryLookBackTable : Table("chat_diary_look_back") {

    val platform = platformColumn()
    val chatId = externalId("chat_id")

    // `week` (monday to sunday) or `month`, the name of a DiaryPeriod.
    val period = varchar("period", 8)

    // the span's first local day as `yyyy-MM-dd`, in the bot's own zone, the way chat_diary names its day.
    val firstDay = varchar("first_day", 10)

    val content = text("content")

    override val primaryKey = PrimaryKey(platform, chatId, period, firstDay)
}
