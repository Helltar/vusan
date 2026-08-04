package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.common.limitTo
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.GroupLogDigestsTable
import com.helltar.vusan.infra.tables.GroupLogTable
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The running transcript of a group chat: every message, not only the ones addressed to the bot.
 * Separate from [com.helltar.vusan.agent.conversation], which is keyed by user **and** chat and only ever
 * holds turns the bot took part in.
 */
class GroupLogRepository(private val config: GroupLogConfig) {

    private companion object {
        // pruning scans the whole chat, so it is amortized over inserts instead of running on each
        // one. the row cap is a ceiling, not a precise limit, and overshooting it briefly is fine.
        const val PRUNE_EVERY_INSERTS = 500
    }

    private val insertsSincePrune = ConcurrentHashMap<Long, AtomicInteger>()

    suspend fun record(entry: GroupLogEntry) {
        // exposed rejects a value longer than the column instead of truncating it, and every field
        // here comes from outside the process, so the fit is enforced at the one place that writes.
        dbTransaction {
            GroupLogTable.insertIgnore {
                it[chatId] = entry.chatId
                it[messageId] = entry.messageId
                it[threadId] = entry.threadId
                it[senderId] = entry.senderId
                it[senderUsername] = entry.senderUsername.fitColumn(USERNAME_COLUMN_CHARS)
                it[senderName] = entry.senderName.fitColumn(NAME_COLUMN_CHARS)
                it[kind] = entry.kind.limitTo(KIND_COLUMN_CHARS)
                it[text] = entry.text
                it[descriptor] = entry.descriptor.fitColumn(DESCRIPTOR_COLUMN_CHARS)
                it[forwardFrom] = entry.forwardFrom.fitColumn(FORWARD_COLUMN_CHARS)
                it[replyToMessageId] = entry.replyToMessageId
                it[sentAt] = entry.sentAt
            }
        }

        if (shouldPrune(entry.chatId)) {
            prune(entry.chatId)
        }
    }

    /**
     * The newest [limit] entries of the window, returned oldest-first so they read as a transcript.
     * The limit is what keeps a month-wide window from being pulled into memory whole; ask
     * [countInWindow] for the real size.
     */
    suspend fun readWindow(
        chatId: Long,
        from: Instant,
        to: Instant,
        limit: Int,
        author: String? = null
    ): List<GroupLogEntry> = dbTransaction {
        GroupLogTable
            .selectAll()
            .where { windowCondition(chatId, from, to, author) }
            .orderBy(GroupLogTable.sentAt to SortOrder.DESC, GroupLogTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
            .reversed()
    }

    suspend fun countInWindow(chatId: Long, from: Instant, to: Instant, author: String? = null): Long =
        dbTransaction {
            GroupLogTable
                .select(GroupLogTable.id)
                .where { windowCondition(chatId, from, to, author) }
                .count()
        }

    /**
     * The tail of the conversation for the `<recent_chat>` prompt block. [excludeMessageId] drops the message
     * that triggered the current turn, which the model is already being shown as the request itself.
     */
    suspend fun recent(
        chatId: Long,
        limit: Int,
        since: Instant,
        excludeMessageId: Long? = null
    ): List<GroupLogEntry> = dbTransaction {
        GroupLogTable
            .selectAll()
            .where {
                var condition = (GroupLogTable.chatId eq chatId) and (GroupLogTable.sentAt greaterEq since)
                excludeMessageId?.let { condition = condition and (GroupLogTable.messageId neq it) }
                condition
            }
            .orderBy(GroupLogTable.sentAt to SortOrder.DESC, GroupLogTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
            .reversed()
    }

    suspend fun digestFor(chatId: Long, day: LocalDate): String? = dbTransaction {
        GroupLogDigestsTable
            .select(GroupLogDigestsTable.content)
            .where { (GroupLogDigestsTable.chatId eq chatId) and (GroupLogDigestsTable.day eq day.toString()) }
            .singleOrNull()
            ?.get(GroupLogDigestsTable.content)
    }

    suspend fun storeDigest(chatId: Long, day: LocalDate, messageCount: Int, content: String) {
        require(content.isNotBlank()) { "Chat log digest must not be blank" }

        dbTransaction {
            // the conflict target has to be named: on a LongIdTable, upsert would otherwise aim at the
            // surrogate id, which never collides, and the insert would break on the unique index instead.
            GroupLogDigestsTable.upsert(
                GroupLogDigestsTable.chatId,
                GroupLogDigestsTable.day,
                onUpdate = {
                    it[GroupLogDigestsTable.messageCount] = messageCount
                    it[GroupLogDigestsTable.content] = content
                    it[GroupLogDigestsTable.createdAt] = Instant.now()
                }
            ) {
                it[GroupLogDigestsTable.chatId] = chatId
                it[GroupLogDigestsTable.day] = day.toString()
                it[GroupLogDigestsTable.messageCount] = messageCount
                it[GroupLogDigestsTable.content] = content
            }
        }
    }

    /** Drops everything recorded for [chatId], transcript and cached digests alike. */
    suspend fun clear(chatId: Long): Int = dbTransaction {
        GroupLogDigestsTable.deleteWhere { GroupLogDigestsTable.chatId eq chatId }
        GroupLogTable.deleteWhere { GroupLogTable.chatId eq chatId }
    }

    private fun shouldPrune(chatId: Long): Boolean {
        val counter = insertsSincePrune.computeIfAbsent(chatId) { AtomicInteger() }

        if (counter.incrementAndGet() < PRUNE_EVERY_INSERTS) return false

        counter.set(0)
        return true
    }

    private suspend fun prune(chatId: Long) {
        val cutoff = Instant.now().minusSeconds(config.retentionDays.toLong() * SECONDS_PER_DAY)

        dbTransaction {
            GroupLogTable.deleteWhere { (GroupLogTable.chatId eq chatId) and (GroupLogTable.sentAt less cutoff) }

            GroupLogDigestsTable.deleteWhere {
                (GroupLogDigestsTable.chatId eq chatId) and
                        (GroupLogDigestsTable.day less LocalDate.ofInstant(cutoff, ZONE).toString())
            }

            val keepMinId =
                GroupLogTable
                    .select(GroupLogTable.id)
                    .where { GroupLogTable.chatId eq chatId }
                    .orderBy(GroupLogTable.id to SortOrder.DESC)
                    .limit(1)
                    .offset((config.maxMessagesPerChat - 1).toLong())
                    .map { it[GroupLogTable.id].value }
                    .firstOrNull() ?: return@dbTransaction

            GroupLogTable.deleteWhere { (GroupLogTable.chatId eq chatId) and (GroupLogTable.id less keepMinId) }
        }
    }
}

private const val SECONDS_PER_DAY = 24L * 60L * 60L
private const val ESCAPE_CHAR = '\\'

// mirror the varchar widths declared in GroupLogTable.
private const val USERNAME_COLUMN_CHARS = 64
private const val NAME_COLUMN_CHARS = 200
private const val KIND_COLUMN_CHARS = 24
private const val DESCRIPTOR_COLUMN_CHARS = 200
private const val FORWARD_COLUMN_CHARS = 128

private fun String?.fitColumn(maxChars: Int): String? = this?.limitTo(maxChars)

private val ZONE: ZoneId get() = ZoneId.systemDefault()

private fun windowCondition(chatId: Long, from: Instant, to: Instant, author: String?): Op<Boolean> {
    val window =
        (GroupLogTable.chatId eq chatId) and
                (GroupLogTable.sentAt greaterEq from) and
                (GroupLogTable.sentAt lessEq to)

    return author?.let { window and authorCondition(it) } ?: window
}

// the model passes whatever the user called the person, so a username matches exactly (with or
// without the `@`) while a display name matches on substring — "olena" has to find "Olena Petrenko".
private fun authorCondition(author: String): Op<Boolean> {
    val needle = author.trim().removePrefix("@").lowercase()
    val contains = LikePattern("%", escapeChar = ESCAPE_CHAR) + LikePattern.ofLiteral(needle, ESCAPE_CHAR) + "%"

    return (GroupLogTable.senderUsername.lowerCase() eq needle) or
            (GroupLogTable.senderName.lowerCase() like contains)
}

private fun ResultRow.toEntry(): GroupLogEntry =
    GroupLogEntry(
        chatId = this[GroupLogTable.chatId],
        messageId = this[GroupLogTable.messageId],
        kind = this[GroupLogTable.kind],
        sentAt = this[GroupLogTable.sentAt],
        threadId = this[GroupLogTable.threadId],
        senderId = this[GroupLogTable.senderId],
        senderUsername = this[GroupLogTable.senderUsername],
        senderName = this[GroupLogTable.senderName],
        text = this[GroupLogTable.text],
        descriptor = this[GroupLogTable.descriptor],
        forwardFrom = this[GroupLogTable.forwardFrom],
        replyToMessageId = this[GroupLogTable.replyToMessageId]
    )
