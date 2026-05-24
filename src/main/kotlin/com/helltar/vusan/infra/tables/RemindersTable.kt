package com.helltar.vusan.infra.tables

import com.helltar.vusan.reminders.Recurrence
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.time.ZoneOffset

object RemindersTable : LongIdTable("reminders") {

    val userId = long("user_id")
    val chatId = long("chat_id")
    val prompt = text("prompt")
    val title = varchar("title", 200).nullable()
    val recurrence = enumerationByName<Recurrence>("recurrence", 16)
    val timezone = varchar("timezone", 64)
    val nextFireAt = timestampWithTimeZone("next_fire_at")
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now(ZoneOffset.UTC) }
    val enabled = bool("enabled").default(true)

    init {
        index(false, userId, enabled)
        index(false, enabled, nextFireAt)
    }
}
