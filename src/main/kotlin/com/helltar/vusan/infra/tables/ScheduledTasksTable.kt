package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.time.ZoneOffset

object ScheduledTasksTable : LongIdTable("scheduled_tasks") {

    val userId = long("user_id")
    val chatId = long("chat_id")
    val prompt = text("prompt")
    val title = varchar("title", 200).nullable()
    val recurrence = varchar("recurrence", 100)
    val timezone = varchar("timezone", 64)
    val nextFireAt = timestampWithTimeZone("next_fire_at")
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now(ZoneOffset.UTC) }
    val enabled = bool("enabled").default(true)

    val creatorMessageId = long("creator_message_id").nullable()
    val creatorUsername = varchar("creator_username", 64).nullable()
    val creatorDisplayName = varchar("creator_display_name", 200).nullable()
    val chatIsPrivate = bool("chat_is_private").default(true)

    init {
        index(false, userId, enabled)
        index(false, enabled, nextFireAt)
    }
}
