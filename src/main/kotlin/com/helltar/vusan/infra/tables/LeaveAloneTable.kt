package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

// a person who asked the bot not to write to them in a chat, and for how long the request holds. one
// row per person per chat: asking again replaces the end, and the end passing is what retires the row.
object LeaveAloneTable : Table("leave_alone") {

    val platform = platformColumn()
    val chatId = externalId("chat_id")
    val userId = externalId("user_id")

    val endsAt = timestamp("ends_at")

    override val primaryKey = PrimaryKey(platform, chatId, userId)
}
