package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ScheduledTasksTable : LongIdTable("scheduled_tasks") {

    val userId = long("user_id")
    val chatId = long("chat_id")
    val title = varchar("title", 200).nullable()
    val prompt = text("prompt")
    val recurrence = varchar("recurrence", 100)
    val timezone = varchar("timezone", 64)
    val nextFireAt = timestamp("next_fire_at")
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    val chatIsPrivate = bool("chat_is_private").default(true)
    val language = varchar("language", 16).nullable()
    val creatorMessageId = long("creator_message_id").nullable()
    val creatorUsername = varchar("creator_username", 64).nullable()
    val creatorDisplayName = varchar("creator_display_name", 200).nullable()

    init {
        index(false, userId, enabled)
        index(false, enabled, nextFireAt)
    }
}
