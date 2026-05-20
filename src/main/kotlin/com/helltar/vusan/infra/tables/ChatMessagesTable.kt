package com.helltar.vusan.infra.tables

import com.helltar.vusan.agent.history.ChatRole
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.time.ZoneOffset

object ChatMessagesTable : IntIdTable("chat_messages") {

    val userId = long("user_id").index()
    val role = enumerationByName<ChatRole>("role", 16)
    val content = text("content")
    val createdAt = timestampWithTimeZone("created_at").clientDefault { OffsetDateTime.now(ZoneOffset.UTC) }
}
