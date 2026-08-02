package com.helltar.vusan.agent.history

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ChatHistoryStateTable
import com.helltar.vusan.infra.tables.ChatHistorySummaryTable
import com.helltar.vusan.infra.tables.ChatMessagesTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.UUID

data class ChatInteraction(
    val id: String,
    val lastMessageId: Long,
    val createdAt: Instant,
    val turns: List<ChatTurn>
) {
    init {
        require(id.isNotBlank()) { "Chat interaction id must not be blank" }
        require(lastMessageId > 0L) { "Chat interaction last message id must be positive" }
        require(turns.firstOrNull()?.role == ChatRole.USER) { "Chat interaction must start with a USER turn" }
    }
}

data class ChatHistorySnapshot(
    val summary: String?,
    val summarizedThroughMessageId: Long,
    val interactions: List<ChatInteraction>,
    val stats: ChatHistoryStats
)

data class ChatHistoryStats(
    val storedInteractions: Int,
    val storedMessages: Int,
    val storedChars: Long,
    val unsummarizedInteractions: Int,
    val unsummarizedMessages: Int
)

class ChatHistoryRepository {

    suspend fun load(userId: Long): ChatHistorySnapshot = dbTransaction {
        normalizeLegacyInteractions(userId)

        val summary = loadSummary(userId)
        val rows = loadRows(userId)
        val allInteractions = rows.toInteractions()
        val unsummarized =
            allInteractions.filter { interaction ->
                interaction.lastMessageId > summary.throughMessageId
            }

        ChatHistorySnapshot(
            summary = summary.content,
            summarizedThroughMessageId = summary.throughMessageId,
            interactions = unsummarized,
            stats =
                ChatHistoryStats(
                    storedInteractions = allInteractions.size,
                    storedMessages = rows.size,
                    storedChars = rows.sumOf { it.turn.content.length.toLong() },
                    unsummarizedInteractions = unsummarized.size,
                    unsummarizedMessages = unsummarized.sumOf { it.turns.size }
                )
        )
    }

    suspend fun appendInteraction(userId: Long, turns: List<ChatTurn>) = dbTransaction {
        if (turns.isEmpty()) return@dbTransaction

        require(turns.first().role == ChatRole.USER) { "Chat interaction must start with a USER turn" }

        val interactionId = UUID.randomUUID().toString()

        ChatMessagesTable.batchInsert(turns) { turn ->
            this[ChatMessagesTable.userId] = userId
            this[ChatMessagesTable.interactionId] = interactionId
            this[ChatMessagesTable.role] = turn.role
            this[ChatMessagesTable.content] = turn.content
            this[ChatMessagesTable.toolCallId] = turn.toolCallId
            this[ChatMessagesTable.toolName] = turn.toolName
            this[ChatMessagesTable.toolIsError] = turn.toolIsError
        }
    }

    suspend fun storeSummary(
        userId: Long,
        expectedThroughMessageId: Long,
        throughMessageId: Long,
        content: String
    ): Boolean = dbTransaction {
        require(throughMessageId > expectedThroughMessageId) { "Summary checkpoint must advance" }
        require(content.isNotBlank()) { "Conversation summary must not be blank" }

        val current = loadSummary(userId)
        if (current.throughMessageId != expectedThroughMessageId) return@dbTransaction false

        val checkpointExists =
            ChatMessagesTable
                .select(ChatMessagesTable.id)
                .where {
                    (ChatMessagesTable.userId eq userId) and
                            (ChatMessagesTable.id eq throughMessageId)
                }
                .limit(1)
                .any()

        if (!checkpointExists) return@dbTransaction false

        val now = Instant.now()
        ChatHistorySummaryTable.upsert(
            onUpdate = {
                it[ChatHistorySummaryTable.content] = content
                it[ChatHistorySummaryTable.throughMessageId] = throughMessageId
                it[ChatHistorySummaryTable.updatedAt] = now
            }
        ) {
            it[ChatHistorySummaryTable.userId] = userId
            it[ChatHistorySummaryTable.content] = content
            it[ChatHistorySummaryTable.throughMessageId] = throughMessageId
            it[ChatHistorySummaryTable.updatedAt] = now
        }

        true
    }

    suspend fun pruneCompacted(
        userId: Long,
        maxStoredInteractions: Int,
        rawRetentionCutoff: Instant
    ): Int = dbTransaction {
        require(maxStoredInteractions > 0) { "maxStoredInteractions must be positive" }

        normalizeLegacyInteractions(userId)

        val summaryThrough = loadSummary(userId).throughMessageId
        if (summaryThrough == 0L) return@dbTransaction 0

        val interactions = loadRows(userId).toInteractions()
        val overflow = (interactions.size - maxStoredInteractions).coerceAtLeast(0)
        val overflowIds = interactions.take(overflow).mapTo(mutableSetOf()) { it.id }
        val expiredIds =
            interactions
                .asSequence()
                .filter { it.createdAt.isBefore(rawRetentionCutoff) }
                .mapTo(mutableSetOf()) { it.id }

        val removableIds =
            (overflowIds + expiredIds)
                .filterTo(mutableSetOf()) { interactionId ->
                    interactions
                        .first { it.id == interactionId }
                        .lastMessageId <= summaryThrough
                }

        if (removableIds.isEmpty()) return@dbTransaction 0

        ChatMessagesTable.deleteWhere {
            (ChatMessagesTable.userId eq userId) and
                    (ChatMessagesTable.interactionId inList removableIds)
        }

        removableIds.size
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
            ChatMessagesTable.deleteWhere { ChatMessagesTable.userId eq userId }
            ChatHistorySummaryTable.deleteWhere { ChatHistorySummaryTable.userId eq userId }

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

    private fun loadRows(userId: Long): List<StoredRow> =
        ChatMessagesTable
            .selectAll()
            .where { ChatMessagesTable.userId eq userId }
            .orderBy(ChatMessagesTable.id to SortOrder.ASC)
            .map {
                StoredRow(
                    messageId = it[ChatMessagesTable.id].value,
                    interactionId = it[ChatMessagesTable.interactionId],
                    createdAt = it[ChatMessagesTable.createdAt],
                    turn =
                        ChatTurn(
                            role = it[ChatMessagesTable.role],
                            content = it[ChatMessagesTable.content],
                            toolCallId = it[ChatMessagesTable.toolCallId],
                            toolName = it[ChatMessagesTable.toolName],
                            toolIsError = it[ChatMessagesTable.toolIsError]
                        )
                )
            }
            .toList()

    private fun loadSummary(userId: Long): StoredSummary =
        ChatHistorySummaryTable
            .select(ChatHistorySummaryTable.content, ChatHistorySummaryTable.throughMessageId)
            .where { ChatHistorySummaryTable.userId eq userId }
            .singleOrNull()
            ?.let {
                StoredSummary(
                    content = it[ChatHistorySummaryTable.content],
                    throughMessageId = it[ChatHistorySummaryTable.throughMessageId]
                )
            }
            ?: StoredSummary(content = null, throughMessageId = 0L)

    // old databases have no interaction ids. recover their complete USER-anchored exchanges once,
    // then every later read and prune can operate on whole interactions instead of raw rows.
    private fun normalizeLegacyInteractions(userId: Long) {
        val legacyRows =
            ChatMessagesTable
                .select(ChatMessagesTable.id, ChatMessagesTable.role, ChatMessagesTable.interactionId)
                .where {
                    (ChatMessagesTable.userId eq userId) and
                            ChatMessagesTable.interactionId.isNull()
                }
                .orderBy(ChatMessagesTable.id to SortOrder.ASC)
                .toList()

        if (legacyRows.isEmpty()) return

        var interactionId: String? = null

        legacyRows.forEach { row ->
            if (row[ChatMessagesTable.role] == ChatRole.USER || interactionId == null) {
                interactionId = UUID.randomUUID().toString()
            }

            val assignedId = checkNotNull(interactionId)
            val messageId = row[ChatMessagesTable.id].value

            ChatMessagesTable.update({ ChatMessagesTable.id eq messageId }) {
                it[ChatMessagesTable.interactionId] = assignedId
            }
        }
    }
}

private data class StoredRow(
    val messageId: Long,
    val interactionId: String?,
    val createdAt: Instant,
    val turn: ChatTurn
)

private data class StoredSummary(val content: String?, val throughMessageId: Long)

private fun List<StoredRow>.toInteractions(): List<ChatInteraction> =
    groupBy { checkNotNull(it.interactionId) { "Chat message row without interaction id after normalization" } }
        .values
        .mapNotNull { rows ->
            val usableRows = rows.dropWhile { it.turn.role != ChatRole.USER }
            if (usableRows.isEmpty()) return@mapNotNull null

            ChatInteraction(
                id = checkNotNull(usableRows.first().interactionId),
                lastMessageId = usableRows.maxOf { it.messageId },
                createdAt = usableRows.minOf { it.createdAt },
                turns = usableRows.map { it.turn }
            )
        }
        .sortedBy { it.lastMessageId }
