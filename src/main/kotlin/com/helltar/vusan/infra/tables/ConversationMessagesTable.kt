package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// one conversation is one person in one chat on one platform: a group and a DM keep separate histories, so
// nothing said in one chat replays as this user's words in another.
object ConversationMessagesTable : LongIdTable("conversation_messages") {

    val platform = platformColumn()
    val userId = externalId("user_id")
    val chatId = externalId("chat_id")
    val interactionId = varchar("interaction_id", 36)
    // a `ChatRole` name, mapped by the repository that owns the enum
    val role = varchar("role", 16)
    val content = text("content")
    val toolCallId = varchar("tool_call_id", 128).nullable()
    val toolName = varchar("tool_name", 128).nullable()
    val toolIsError = bool("tool_is_error").nullable()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    init {
        // every read filters on the whole scope. `interaction_id` has no index: its one query narrows by
        // scope first, and a conversation is capped at `MAX_STORED_INTERACTIONS`, too few rows to be worth an
        // index write per message.
        index(false, platform, userId, chatId)
    }
}
