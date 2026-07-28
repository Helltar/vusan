package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

object ChatHistoryStateTable : Table("chat_history_state") {

    val userId = long("user_id")
    val revision = long("revision")

    override val primaryKey = PrimaryKey(userId)
}
