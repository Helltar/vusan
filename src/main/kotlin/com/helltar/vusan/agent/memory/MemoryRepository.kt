package com.helltar.vusan.agent.memory

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.MemoryTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Durable memory, separate from the conversation history ([com.helltar.vusan.agent.history]).
 * Entries survive a history wipe; [MemoryScope] decides whether a row is personal ([MemoryScope.USER],
 * keyed by `userId`) or shared by a group ([MemoryScope.CHAT], keyed by `chatId`).
 */
class MemoryRepository(private val maxEntriesPerScope: Int = 10) {

    suspend fun load(scope: MemoryScope, ownerId: Long): List<MemoryEntry> = dbTransaction {
        MemoryTable
            .selectAll()
            .where { (MemoryTable.scope eq scope) and (MemoryTable.ownerId eq ownerId) }
            .orderBy(MemoryTable.id to SortOrder.ASC)
            .map {
                MemoryEntry(
                    id = it[MemoryTable.id].value,
                    content = it[MemoryTable.content],
                    createdAt = it[MemoryTable.createdAt]
                )
            }
    }

    suspend fun add(scope: MemoryScope, ownerId: Long, content: String): Long = dbTransaction {
        require(content.isNotBlank()) { "Memory content must not be blank" }

        val id =
            MemoryTable.insertAndGetId {
                it[MemoryTable.scope] = scope
                it[MemoryTable.ownerId] = ownerId
                it[MemoryTable.content] = content
            }.value

        trim(scope, ownerId)
        id
    }

    /**
     * Deletes the entry with [id], but only if it belongs to the caller's own user memory ([userId])
     * or to the current chat's group memory ([chatId]). The ownership check lives in the query, so a
     * caller can never delete another user's memory or another chat's memory. Returns whether a row was removed.
     */
    suspend fun forget(id: Long, userId: Long, chatId: Long): Boolean = dbTransaction {
        val deleted =
            MemoryTable.deleteWhere {
                (MemoryTable.id eq id) and (
                        ((MemoryTable.scope eq MemoryScope.USER) and (MemoryTable.ownerId eq userId)) or
                                ((MemoryTable.scope eq MemoryScope.CHAT) and (MemoryTable.ownerId eq chatId))
                        )
            }

        deleted > 0
    }

    suspend fun clearScope(scope: MemoryScope, ownerId: Long): Int = dbTransaction {
        MemoryTable.deleteWhere { (MemoryTable.scope eq scope) and (MemoryTable.ownerId eq ownerId) }
    }

    private fun trim(scope: MemoryScope, ownerId: Long) {
        val keepMinId =
            MemoryTable
                .select(MemoryTable.id)
                .where { (MemoryTable.scope eq scope) and (MemoryTable.ownerId eq ownerId) }
                .orderBy(MemoryTable.id to SortOrder.DESC)
                .limit(1)
                .offset((maxEntriesPerScope - 1).toLong())
                .map { it[MemoryTable.id].value }
                .firstOrNull() ?: return

        MemoryTable.deleteWhere {
            (MemoryTable.scope eq scope) and (MemoryTable.ownerId eq ownerId) and (MemoryTable.id less keepMinId)
        }
    }
}
