package com.helltar.vusan.infra.tables

import com.helltar.vusan.agent.conversation.ChatRole
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

// one conversation is one person in one chat. the same user writing in a group and in a DM keeps two
// separate histories, so nothing said in one chat can be replayed as this user's own words in another.
object ConversationMessagesTable : LongIdTable("conversation_messages") {

    val userId = long("user_id")
    val chatId = long("chat_id")
    val interactionId = varchar("interaction_id", 36).index()
    val role = enumerationByName<ChatRole>("role", 16)
    val content = text("content")
    val toolCallId = varchar("tool_call_id", 128).nullable()
    val toolName = varchar("tool_name", 128).nullable()
    val toolIsError = bool("tool_is_error").nullable()
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    init {
        // every read filters on the pair, never on one half of it.
        index(false, userId, chatId)
    }
}
