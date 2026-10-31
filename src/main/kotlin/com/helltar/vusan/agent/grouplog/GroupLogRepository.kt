package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.common.limitTo
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ChatDiaryLookBackTable
import com.helltar.vusan.infra.tables.ChatDiaryTable
import com.helltar.vusan.infra.tables.GroupLogTable
import com.helltar.vusan.request.ChatRef
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneId

/**
 * The running transcript of a group chat: every message, not only those addressed to the bot. Unlike
 * [com.helltar.vusan.agent.conversation], which is keyed by user **and** chat and holds only turns the bot
 * took part in.
 */
class GroupLogRepository(
    private val config: GroupLogConfig,
    /**
     * The zone a transcript day is cut on; diary entries are keyed by day, so every reader takes it from
     * here.
     */
    val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** How far back the log reaches, so a reader can refuse a window it could never fill. */
    val retentionDays: Int
        get() = config.retentionDays

    suspend fun record(entry: GroupLogEntry) {
        // exposed rejects an over-long value instead of truncating, and every field comes from outside, so
        // the fit is enforced at the one writer.
        dbTransaction {
            GroupLogTable.insertIgnore {
                it[platform] = entry.chat.platform
                it[chatId] = entry.chat.id
                it[messageId] = entry.messageId
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
    }

    /**
     * Applies an edit to a message already in the transcript, changing only what telegram lets an edit
     * change. A message the log never saw is not inserted: its surroundings are missing too, and one
     * backfilled row would read as if nothing else was said.
     */
    suspend fun recordEdit(entry: GroupLogEntry): Boolean {
        val editedMessageId = entry.messageId ?: return false

        return dbTransaction {
            GroupLogTable.update({
                inChat(entry.chat) and (GroupLogTable.messageId eq editedMessageId)
            }) {
                it[kind] = entry.kind.limitTo(KIND_COLUMN_CHARS)
                it[text] = entry.text
                it[descriptor] = entry.descriptor.fitColumn(DESCRIPTOR_COLUMN_CHARS)
            } > 0
        }
    }

    /**
     * The newest [limit] entries of the window, oldest first. The limit keeps a month-wide window out of
     * memory; [countInWindow] gives the real size.
     */
    suspend fun readWindow(
        chat: ChatRef,
        from: Instant,
        to: Instant,
        limit: Int,
        author: String? = null,
    ): List<GroupLogEntry> = dbTransaction {
        GroupLogTable
            .selectAll()
            .where { windowCondition(chat, from, to, author) }
            .orderBy(GroupLogTable.sentAt to SortOrder.DESC, GroupLogTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
            .reversed()
    }

    suspend fun countInWindow(chat: ChatRef, from: Instant, to: Instant, author: String? = null): Long =
        dbTransaction {
            GroupLogTable
                .select(GroupLogTable.id)
                .where { windowCondition(chat, from, to, author) }
                .count()
        }

    /**
     * Every entry of the window mentioning [needle] in its text, media label or forwarded channel,
     * case-insensitively, oldest first. Matched here rather than in SQL, since SQLite folds case for ASCII
     * only and `LIKE` would miss a capitalized cyrillic name. Retention and
     * [GroupLogConfig.maxMessagesPerChat] keep walking every row affordable.
     */
    suspend fun searchWindow(
        chat: ChatRef,
        from: Instant,
        to: Instant,
        needle: String,
        author: String? = null,
    ): List<GroupLogEntry> = dbTransaction {
        GroupLogTable
            .selectAll()
            .where { windowCondition(chat, from, to, author) }
            .orderBy(GroupLogTable.sentAt to SortOrder.ASC, GroupLogTable.id to SortOrder.ASC)
            .mapNotNull { row -> row.toEntry().takeIf { it.mentions(needle) } }
    }

    /**
     * The tail of the conversation for `<recent_chat>`. [excludeMessageId] drops the message that triggered
     * the turn, which the model already sees as the request.
     */
    suspend fun recent(
        chat: ChatRef,
        limit: Int,
        since: Instant,
        excludeMessageId: String? = null,
    ): List<GroupLogEntry> = dbTransaction {
        GroupLogTable
            .selectAll()
            .where {
                var condition = inChat(chat) and (GroupLogTable.sentAt greaterEq since)

                excludeMessageId?.let {
                    // the bot's rows have no message id, and `!=` against null is null in SQL: without the
                    // null arm every bot reply would be excluded too.
                    condition = condition and ((GroupLogTable.messageId neq it) or GroupLogTable.messageId.isNull())
                }

                condition
            }
            .orderBy(GroupLogTable.sentAt to SortOrder.DESC, GroupLogTable.id to SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
            .reversed()
    }

    /** The chats people wrote in between [from] and [to]; the bot's own lines do not count. */
    suspend fun activeChats(from: Instant, to: Instant): List<ChatRef> = dbTransaction {
        GroupLogTable
            .select(GroupLogTable.platform, GroupLogTable.chatId)
            .where {
                (GroupLogTable.sentAt greaterEq from) and
                        (GroupLogTable.sentAt lessEq to) and
                        (GroupLogTable.kind neq GroupLogEntry.BOT_KIND)
            }
            .withDistinct()
            .map { ChatRef(it[GroupLogTable.platform], it[GroupLogTable.chatId]) }
    }

    /**
     * Who wrote in [chat] since [since], how much and when last. Senders without an id of their own (an
     * anonymous admin, a linked channel) are left out, as they are not one person.
     */
    suspend fun authorActivity(chat: ChatRef, since: Instant): List<AuthorActivity> = dbTransaction {
        val messages = GroupLogTable.id.count()
        val lastSeenAt = GroupLogTable.sentAt.max()
        val username = GroupLogTable.senderUsername.max()
        val name = GroupLogTable.senderName.max()

        GroupLogTable
            .select(GroupLogTable.senderId, messages, lastSeenAt, username, name)
            .where {
                inChat(chat) and
                        (GroupLogTable.sentAt greaterEq since) and
                        (GroupLogTable.kind neq GroupLogEntry.BOT_KIND) and
                        GroupLogTable.senderId.isNotNull()
            }
            .groupBy(GroupLogTable.senderId)
            .mapNotNull { row ->
                AuthorActivity(
                    senderId = row[GroupLogTable.senderId] ?: return@mapNotNull null,
                    name = row[username] ?: row[name] ?: return@mapNotNull null,
                    messages = row[messages],
                    lastSeenAt = row[lastSeenAt] ?: return@mapNotNull null,
                )
            }
    }

    /** Drops everything recorded for [chat]: the transcript and the diary written from it. */
    suspend fun clear(chat: ChatRef): Int = dbTransaction {
        ChatDiaryTable.deleteWhere {
            (ChatDiaryTable.platform eq chat.platform) and (ChatDiaryTable.chatId eq chat.id)
        }

        ChatDiaryLookBackTable.deleteWhere {
            (ChatDiaryLookBackTable.platform eq chat.platform) and (ChatDiaryLookBackTable.chatId eq chat.id)
        }

        GroupLogTable.deleteWhere { inChat(chat) }
    }

    /**
     * Retention for chats that have one: everything past [GroupLogConfig.retentionDays], and the oldest rows
     * of a chat over [GroupLogConfig.maxMessagesPerChat].
     *
     * At most [maxChats] chats per pass, oldest row first, so a quiet chat is not left behind a busy one. The
     * row cap may be overshot between passes.
     */
    suspend fun pruneExpired(maxChats: Int): Int {
        val cutoff = retentionCutoff()

        val chats =
            dbTransaction {
                val expired =
                    GroupLogTable
                        .select(GroupLogTable.platform, GroupLogTable.chatId)
                        .where { GroupLogTable.sentAt less cutoff }
                        .withDistinct()
                        .limit(maxChats)
                        .map { ChatRef(it[GroupLogTable.platform], it[GroupLogTable.chatId]) }

                // a busy chat can be over the row cap without anything old enough to expire.
                val overCap =
                    GroupLogTable
                        .select(GroupLogTable.platform, GroupLogTable.chatId)
                        .groupBy(GroupLogTable.platform, GroupLogTable.chatId)
                        .having { GroupLogTable.id.count() greater config.maxMessagesPerChat.toLong() }
                        .limit(maxChats)
                        .map { ChatRef(it[GroupLogTable.platform], it[GroupLogTable.chatId]) }

                (expired + overCap).distinct()
            }

        chats.forEach { prune(it) }

        return chats.size
    }

    private fun retentionCutoff(): Instant =
        Instant.now().minusSeconds(config.retentionDays.toLong() * SECONDS_PER_DAY)

    private suspend fun prune(chat: ChatRef) {
        val cutoff = retentionCutoff()

        dbTransaction {
            GroupLogTable.deleteWhere { inChat(chat) and (GroupLogTable.sentAt less cutoff) }

            val keepMinId =
                GroupLogTable
                    .select(GroupLogTable.id)
                    .where { inChat(chat) }
                    .orderBy(GroupLogTable.id to SortOrder.DESC)
                    .limit(1)
                    .offset((config.maxMessagesPerChat - 1).toLong())
                    .map { it[GroupLogTable.id].value }
                    .firstOrNull() ?: return@dbTransaction

            GroupLogTable.deleteWhere { inChat(chat) and (GroupLogTable.id less keepMinId) }
        }
    }
}

private const val SECONDS_PER_DAY = 24L * 60L * 60L
private const val ESCAPE_CHAR = '\\'

// mirror the varchar widths in GroupLogTable.
private const val USERNAME_COLUMN_CHARS = 64
private const val NAME_COLUMN_CHARS = 200
private const val KIND_COLUMN_CHARS = 24
private const val DESCRIPTOR_COLUMN_CHARS = 200
private const val FORWARD_COLUMN_CHARS = 128

private fun String?.fitColumn(maxChars: Int): String? = this?.limitTo(maxChars)

private fun inChat(chat: ChatRef): Op<Boolean> =
    (GroupLogTable.platform eq chat.platform) and (GroupLogTable.chatId eq chat.id)

private fun windowCondition(chat: ChatRef, from: Instant, to: Instant, author: String?): Op<Boolean> {
    val window =
        inChat(chat) and
                (GroupLogTable.sentAt greaterEq from) and
                (GroupLogTable.sentAt lessEq to)

    return author?.let { window and authorCondition(it) } ?: window
}

// the model passes whatever the user called the person: a username matches exactly (with or without `@`), a
// display name by substring, so "olena" finds "Olena Petrenko".
private fun authorCondition(author: String): Op<Boolean> {
    val needle = author.trim().removePrefix("@").lowercase()
    val contains = LikePattern("%", escapeChar = ESCAPE_CHAR) + LikePattern.ofLiteral(needle, ESCAPE_CHAR) + "%"

    return (GroupLogTable.senderUsername.lowerCase() eq needle) or
            (GroupLogTable.senderName.lowerCase() like contains)
}

private fun GroupLogEntry.mentions(needle: String): Boolean =
    listOfNotNull(text, descriptor, forwardFrom).any { it.contains(needle, ignoreCase = true) }

private fun ResultRow.toEntry(): GroupLogEntry =
    GroupLogEntry(
        chat = ChatRef(this[GroupLogTable.platform], this[GroupLogTable.chatId]),
        messageId = this[GroupLogTable.messageId],
        kind = this[GroupLogTable.kind],
        sentAt = this[GroupLogTable.sentAt],
        senderId = this[GroupLogTable.senderId],
        senderUsername = this[GroupLogTable.senderUsername],
        senderName = this[GroupLogTable.senderName],
        text = this[GroupLogTable.text],
        descriptor = this[GroupLogTable.descriptor],
        forwardFrom = this[GroupLogTable.forwardFrom],
        replyToMessageId = this[GroupLogTable.replyToMessageId],
    )
