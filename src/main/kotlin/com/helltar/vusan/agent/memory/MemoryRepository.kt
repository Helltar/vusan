package com.helltar.vusan.agent.memory

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.MemoryTable
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.UserRef
import org.jetbrains.exposed.v1.core.Op
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
 * Durable memory, separate from the conversation history ([com.helltar.vusan.agent.conversation]).
 * Entries survive a history wipe; a [MemoryOwner] says whether a row is one person's or one group's,
 * and on which platform.
 */
class MemoryRepository(private val maxEntriesPerScope: Int = 10) {

    suspend fun load(owner: MemoryOwner): List<MemoryEntry> = dbTransaction {
        MemoryTable
            .selectAll()
            .where { ownedBy(owner) }
            .orderBy(MemoryTable.id to SortOrder.ASC)
            .map {
                MemoryEntry(
                    id = it[MemoryTable.id].value,
                    content = it[MemoryTable.content],
                )
            }
    }

    suspend fun add(owner: MemoryOwner, content: String): Long = dbTransaction {
        require(content.isNotBlank()) { "Memory content must not be blank" }

        val id =
            MemoryTable.insertAndGetId {
                it[MemoryTable.platform] = owner.platform
                it[MemoryTable.scope] = owner.scope
                it[MemoryTable.ownerId] = owner.id
                it[MemoryTable.content] = content
            }.value

        trim(owner)
        id
    }

    /**
     * Deletes the entry with [id], but only if it belongs to [user]'s own memory or to [chat]'s shared
     * memory. The ownership check lives in the query, so a caller can never delete another person's
     * memory or another chat's. Returns whether a row was removed.
     */
    suspend fun forget(id: Long, user: UserRef, chat: ChatRef): Boolean = dbTransaction {
        val deleted =
            MemoryTable.deleteWhere {
                (MemoryTable.id eq id) and (ownedBy(user.memoryOwner) or ownedBy(chat.memoryOwner))
            }

        deleted > 0
    }

    suspend fun clearScope(owner: MemoryOwner): Int = dbTransaction {
        MemoryTable.deleteWhere { ownedBy(owner) }
    }

    private fun trim(owner: MemoryOwner) {
        val keepMinId =
            MemoryTable
                .select(MemoryTable.id)
                .where { ownedBy(owner) }
                .orderBy(MemoryTable.id to SortOrder.DESC)
                .limit(1)
                .offset((maxEntriesPerScope - 1).toLong())
                .map { it[MemoryTable.id].value }
                .firstOrNull() ?: return

        MemoryTable.deleteWhere { ownedBy(owner) and (MemoryTable.id less keepMinId) }
    }
}

private fun ownedBy(owner: MemoryOwner): Op<Boolean> =
    (MemoryTable.platform eq owner.platform) and
            (MemoryTable.scope eq owner.scope) and
            (MemoryTable.ownerId eq owner.id)
