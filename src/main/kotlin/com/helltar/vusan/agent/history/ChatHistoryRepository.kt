package com.helltar.vusan.agent.history

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ChatMessagesTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

class ChatHistoryRepository(private val maxMessagesPerUser: Int = 120) {

    suspend fun load(userId: Long): List<ChatTurn> = dbTransaction {
        ChatMessagesTable
            .selectAll()
            .where { ChatMessagesTable.userId eq userId }
            .orderBy(ChatMessagesTable.id to SortOrder.ASC)
            .map {
                ChatTurn(
                    role = it[ChatMessagesTable.role],
                    content = it[ChatMessagesTable.content],
                    toolCallId = it[ChatMessagesTable.toolCallId],
                    toolName = it[ChatMessagesTable.toolName],
                    toolIsError = it[ChatMessagesTable.toolIsError]
                )
            }
            .toList()
    }

    suspend fun appendTurns(userId: Long, turns: List<ChatTurn>) = dbTransaction {
        for (turn in turns) {
            ChatMessagesTable.insert {
                it[ChatMessagesTable.userId] = userId
                it[role] = turn.role
                it[content] = turn.content
                it[toolCallId] = turn.toolCallId
                it[toolName] = turn.toolName
                it[toolIsError] = turn.toolIsError
            }
        }

        if (turns.isNotEmpty()) {
            trim(userId)
        }
    }

    suspend fun clear(userId: Long) = dbTransaction {
        ChatMessagesTable
            .deleteWhere { ChatMessagesTable.userId eq userId }
    }

    private fun trim(userId: Long) {
        val keepMinId =
            ChatMessagesTable
                .select(ChatMessagesTable.id)
                .where { ChatMessagesTable.userId eq userId }
                .orderBy(ChatMessagesTable.id to SortOrder.DESC)
                .limit(1)
                .offset((maxMessagesPerUser - 1).toLong())
                .map { it[ChatMessagesTable.id].value }
                .firstOrNull() ?: return

        ChatMessagesTable.deleteWhere {
            ChatMessagesTable.userId eq userId and (ChatMessagesTable.id less keepMinId)
        }
    }
}
