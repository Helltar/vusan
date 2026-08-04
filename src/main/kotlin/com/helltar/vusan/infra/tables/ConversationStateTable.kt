package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

object ConversationStateTable : Table("conversation_state") {

    val userId = long("user_id")
    val chatId = long("chat_id")
    val revision = long("revision")

    override val primaryKey = PrimaryKey(userId, chatId)
}
