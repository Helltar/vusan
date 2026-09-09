package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

object ConversationStateTable : Table("conversation_state") {

    val platform = platformColumn()
    val userId = externalId("user_id")
    val chatId = externalId("chat_id")
    val revision = long("revision")

    override val primaryKey = PrimaryKey(platform, userId, chatId)
}
