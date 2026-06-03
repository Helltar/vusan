package com.helltar.vusan.infra.tables

import com.helltar.vusan.agent.history.ChatRole
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ChatMessagesTable : LongIdTable("chat_messages") {

    val userId = long("user_id").index()
    val role = enumerationByName<ChatRole>("role", 16)
    val content = text("content")
    val toolCallId = varchar("tool_call_id", 128).nullable()
    val toolName = varchar("tool_name", 128).nullable()
    val toolIsError = bool("tool_is_error").nullable()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
}
