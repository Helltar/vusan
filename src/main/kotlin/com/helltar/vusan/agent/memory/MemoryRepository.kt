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
class MemoryRepository(private val maxEntriesPerScope: Int = MAX_ENTRIES_PER_SCOPE) {

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

        insert(owner, content).also { trim(owner) }
    }

    /**
     * Saves [content] in place of [owner]'s entry [replaces], so a detail that was refined or turned
     * out wrong does not stay beside its correction. Returns the new entry's id, or `null` with
     * nothing saved when [owner] has no such entry — another owner's is never touched.
     */
    suspend fun replace(owner: MemoryOwner, replaces: Long, content: String): Long? = dbTransaction {
        require(content.isNotBlank()) { "Memory content must not be blank" }

        val removed = MemoryTable.deleteWhere { (MemoryTable.id eq replaces) and ownedBy(owner) }

        if (removed > 0) insert(owner, content) else null
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

    private fun insert(owner: MemoryOwner, content: String): Long =
        MemoryTable.insertAndGetId {
            it[MemoryTable.platform] = owner.platform
            it[MemoryTable.scope] = owner.scope
            it[MemoryTable.ownerId] = owner.id
            it[MemoryTable.content] = content
        }.value

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

    companion object {
        // the oldest entry is evicted past this without a word, so it is sized for what a person
        // accumulates over months — name, place, work, language, a handful of preferences — not weeks.
        const val MAX_ENTRIES_PER_SCOPE = 20
    }
}

private fun ownedBy(owner: MemoryOwner): Op<Boolean> =
    (MemoryTable.platform eq owner.platform) and
            (MemoryTable.scope eq owner.scope) and
            (MemoryTable.ownerId eq owner.id)
