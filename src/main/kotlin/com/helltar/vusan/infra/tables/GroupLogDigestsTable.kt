package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// one cached recap per closed local day, so a repeated "what happened this week" costs nothing.
// only days that can no longer receive messages are stored — see GroupLogRepository.
object GroupLogDigestsTable : LongIdTable("group_log_digests") {

    val chatId = long("chat_id")

    // local date as `yyyy-MM-dd`; the zone is the bot's own, the same one the model is told about.
    val day = varchar("day", 10)

    val messageCount = integer("message_count")
    val content = text("content")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    init {
        uniqueIndex(chatId, day)
    }
}
