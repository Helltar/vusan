package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// one cached recap per closed local day, so a repeated "what happened this week" costs nothing.
// only days that can no longer receive messages are stored — see GroupLogRepository.
object GroupLogDigestsTable : Table("group_log_digests") {

    val platform = platformColumn()
    val chatId = externalId("chat_id")

    // local date as `yyyy-MM-dd`; the zone is the bot's own, the same one the model is told about.
    val day = varchar("day", 10)

    // how many lines of that day the recap was actually made from: the transcript it summarizes is cut
    // to what one digest prompt may cost, so this is coverage rather than the size of the day.
    val messageCount = integer("message_count")
    val content = text("content")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(platform, chatId, day)
}
