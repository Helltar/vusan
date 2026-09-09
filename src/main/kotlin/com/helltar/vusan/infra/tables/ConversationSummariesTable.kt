package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ConversationSummariesTable : Table("conversation_summaries") {

    val platform = platformColumn()
    val userId = externalId("user_id")
    val chatId = externalId("chat_id")
    val content = text("content")
    val throughMessageId = long("through_message_id")
    val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(platform, userId, chatId)
}
