package com.helltar.vusan.agent.history

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ChatHistoryStateTable
import com.helltar.vusan.infra.tables.ChatMessagesTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

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
        ChatMessagesTable.batchInsert(turns) { turn ->
            this[ChatMessagesTable.userId] = userId
            this[ChatMessagesTable.role] = turn.role
            this[ChatMessagesTable.content] = turn.content
            this[ChatMessagesTable.toolCallId] = turn.toolCallId
            this[ChatMessagesTable.toolName] = turn.toolName
            this[ChatMessagesTable.toolIsError] = turn.toolIsError
        }

        if (turns.isNotEmpty()) {
            trim(userId)
        }
    }

    suspend fun revision(userId: Long): Long = dbTransaction {
        ChatHistoryStateTable
            .select(ChatHistoryStateTable.revision)
            .where { ChatHistoryStateTable.userId eq userId }
            .singleOrNull()
            ?.get(ChatHistoryStateTable.revision)
            ?: 0L
    }

    suspend fun clear(userId: Long) {
        dbTransaction {
            ChatMessagesTable
                .deleteWhere { ChatMessagesTable.userId eq userId }

            ChatHistoryStateTable.upsert(
                onUpdate = {
                    it[ChatHistoryStateTable.revision] = ChatHistoryStateTable.revision + 1L
                }
            ) {
                it[ChatHistoryStateTable.userId] = userId
                it[ChatHistoryStateTable.revision] = 1L
            }
        }
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
