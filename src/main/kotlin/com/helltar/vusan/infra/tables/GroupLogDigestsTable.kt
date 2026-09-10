package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

// one cached recap per closed local day, so a repeated "what happened this week" costs nothing.
// only days that can no longer receive messages are stored — see GroupLogRepository.
object GroupLogDigestsTable : Table("group_log_digests") {

    val platform = platformColumn()
    val chatId = externalId("chat_id")

    // local date as `yyyy-MM-dd`; the zone is the bot's own, the same one the model is told about.
    val day = varchar("day", 10)

    val content = text("content")

    override val primaryKey = PrimaryKey(platform, chatId, day)
}
