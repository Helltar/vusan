package com.helltar.vusan.agent.conversation

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ConversationStateTable
import com.helltar.vusan.infra.tables.ConversationSummariesTable
import com.helltar.vusan.infra.tables.ConversationMessagesTable
import com.helltar.vusan.request.ConversationScope
import org.jetbrains.exposed.v1.core.Op
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

data class ConversationInteraction(
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

data class ConversationSnapshot(
    val summary: String?,
    val summarizedThroughMessageId: Long,
    val interactions: List<ConversationInteraction>,
    val stats: ConversationStats
)

data class ConversationStats(
    val storedInteractions: Int,
    val storedMessages: Int,
    val storedChars: Long,
    val unsummarizedInteractions: Int,
    val unsummarizedMessages: Int
)

/**
 * The stored conversation between one person and the bot **in one chat**. Every method is keyed by a
 * whole [ConversationScope]: history is replayed to the model as that user's own `user`/`assistant`
 * turns, so a single global thread per user would let a private exchange resurface inside a group —
 * and, across messengers, would let two people who happen to share an id read each other's. What
 * should travel between chats is durable memory ([com.helltar.vusan.agent.memory]), not raw turns.
 */
class ConversationRepository {

    suspend fun load(scope: ConversationScope): ConversationSnapshot = dbTransaction {
        val summary = loadSummary(scope)
        val rows = loadRows(scope)
        val allInteractions = rows.toInteractions()
        val unsummarized =
            allInteractions.filter { interaction ->
                interaction.lastMessageId > summary.throughMessageId
            }

        ConversationSnapshot(
            summary = summary.content,
            summarizedThroughMessageId = summary.throughMessageId,
            interactions = unsummarized,
            stats =
                ConversationStats(
                    storedInteractions = allInteractions.size,
                    storedMessages = rows.size,
                    storedChars = rows.sumOf { it.turn.content.length.toLong() },
                    unsummarizedInteractions = unsummarized.size,
                    unsummarizedMessages = unsummarized.sumOf { it.turns.size }
                )
        )
    }

    suspend fun appendInteraction(scope: ConversationScope, turns: List<ChatTurn>) = dbTransaction {
        if (turns.isEmpty()) return@dbTransaction

        require(turns.first().role == ChatRole.USER) { "Chat interaction must start with a USER turn" }

        val interactionId = UUID.randomUUID().toString()

        ConversationMessagesTable.batchInsert(turns) { turn ->
            this[ConversationMessagesTable.platform] = scope.platform
            this[ConversationMessagesTable.userId] = scope.user.id
            this[ConversationMessagesTable.chatId] = scope.chat.id
            this[ConversationMessagesTable.interactionId] = interactionId
            this[ConversationMessagesTable.role] = turn.role
            this[ConversationMessagesTable.content] = turn.content
            this[ConversationMessagesTable.toolCallId] = turn.toolCallId
            this[ConversationMessagesTable.toolName] = turn.toolName
            this[ConversationMessagesTable.toolIsError] = turn.toolIsError
        }
    }

    suspend fun storeSummary(
        scope: ConversationScope,
        expectedThroughMessageId: Long,
        throughMessageId: Long,
        content: String
    ): Boolean = dbTransaction {
        require(throughMessageId > expectedThroughMessageId) { "Summary checkpoint must advance" }
        require(content.isNotBlank()) { "Conversation summary must not be blank" }

        val current = loadSummary(scope)
        if (current.throughMessageId != expectedThroughMessageId) return@dbTransaction false

        val checkpointExists =
            ConversationMessagesTable
                .select(ConversationMessagesTable.id)
                .where { conversationIs(scope) and (ConversationMessagesTable.id eq throughMessageId) }
                .limit(1)
                .any()

        if (!checkpointExists) return@dbTransaction false

        val now = Instant.now()
        ConversationSummariesTable.upsert(
            onUpdate = {
                it[ConversationSummariesTable.content] = content
                it[ConversationSummariesTable.throughMessageId] = throughMessageId
                it[ConversationSummariesTable.updatedAt] = now
            }
        ) {
            it[ConversationSummariesTable.platform] = scope.platform
            it[ConversationSummariesTable.userId] = scope.user.id
            it[ConversationSummariesTable.chatId] = scope.chat.id
            it[ConversationSummariesTable.content] = content
            it[ConversationSummariesTable.throughMessageId] = throughMessageId
            it[ConversationSummariesTable.updatedAt] = now
        }

        true
    }

    suspend fun pruneCompacted(
        scope: ConversationScope,
        maxStoredInteractions: Int,
        rawRetentionCutoff: Instant
    ): Int = dbTransaction {
        require(maxStoredInteractions > 0) { "maxStoredInteractions must be positive" }

        val summaryThrough = loadSummary(scope).throughMessageId
        if (summaryThrough == 0L) return@dbTransaction 0

        val interactions = loadRows(scope).toInteractions()
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

        ConversationMessagesTable.deleteWhere {
            conversationIs(scope) and (ConversationMessagesTable.interactionId inList removableIds)
        }

        removableIds.size
    }

    // when this user last exchanged anything with the bot in this chat. rows are inserted in turn
    // order, so the newest id carries the newest timestamp and no aggregate is needed. the current
    // turn is stored only after the run, so during a turn this is the previous exchange.
    suspend fun lastInteractionAt(scope: ConversationScope): Instant? = dbTransaction {
        ConversationMessagesTable
            .select(ConversationMessagesTable.createdAt)
            .where { conversationIs(scope) }
            .orderBy(ConversationMessagesTable.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(ConversationMessagesTable.createdAt)
    }

    suspend fun revision(scope: ConversationScope): Long = dbTransaction {
        ConversationStateTable
            .select(ConversationStateTable.revision)
            .where { stateIs(scope) }
            .singleOrNull()
            ?.get(ConversationStateTable.revision)
            ?: 0L
    }

    suspend fun clear(scope: ConversationScope) {
        dbTransaction {
            ConversationMessagesTable.deleteWhere { conversationIs(scope) }
            ConversationSummariesTable.deleteWhere { summaryIs(scope) }

            ConversationStateTable.upsert(
                onUpdate = {
                    it[ConversationStateTable.revision] = ConversationStateTable.revision + 1L
                }
            ) {
                it[ConversationStateTable.platform] = scope.platform
                it[ConversationStateTable.userId] = scope.user.id
                it[ConversationStateTable.chatId] = scope.chat.id
                it[ConversationStateTable.revision] = 1L
            }
        }
    }

    private fun loadRows(scope: ConversationScope): List<StoredRow> =
        ConversationMessagesTable
            .selectAll()
            .where { conversationIs(scope) }
            .orderBy(ConversationMessagesTable.id to SortOrder.ASC)
            .map {
                StoredRow(
                    messageId = it[ConversationMessagesTable.id].value,
                    interactionId = it[ConversationMessagesTable.interactionId],
                    createdAt = it[ConversationMessagesTable.createdAt],
                    turn =
                        ChatTurn(
                            role = it[ConversationMessagesTable.role],
                            content = it[ConversationMessagesTable.content],
                            toolCallId = it[ConversationMessagesTable.toolCallId],
                            toolName = it[ConversationMessagesTable.toolName],
                            toolIsError = it[ConversationMessagesTable.toolIsError]
                        )
                )
            }
            .toList()

    private fun loadSummary(scope: ConversationScope): StoredSummary =
        ConversationSummariesTable
            .select(ConversationSummariesTable.content, ConversationSummariesTable.throughMessageId)
            .where { summaryIs(scope) }
            .singleOrNull()
            ?.let {
                StoredSummary(
                    content = it[ConversationSummariesTable.content],
                    throughMessageId = it[ConversationSummariesTable.throughMessageId]
                )
            }
            ?: StoredSummary(content = null, throughMessageId = 0L)

}

private fun conversationIs(scope: ConversationScope): Op<Boolean> =
    (ConversationMessagesTable.platform eq scope.platform) and
            (ConversationMessagesTable.userId eq scope.user.id) and
            (ConversationMessagesTable.chatId eq scope.chat.id)

private fun summaryIs(scope: ConversationScope): Op<Boolean> =
    (ConversationSummariesTable.platform eq scope.platform) and
            (ConversationSummariesTable.userId eq scope.user.id) and
            (ConversationSummariesTable.chatId eq scope.chat.id)

private fun stateIs(scope: ConversationScope): Op<Boolean> =
    (ConversationStateTable.platform eq scope.platform) and
            (ConversationStateTable.userId eq scope.user.id) and
            (ConversationStateTable.chatId eq scope.chat.id)

private data class StoredRow(
    val messageId: Long,
    val interactionId: String,
    val createdAt: Instant,
    val turn: ChatTurn
)

private data class StoredSummary(val content: String?, val throughMessageId: Long)

private fun List<StoredRow>.toInteractions(): List<ConversationInteraction> =
    groupBy { it.interactionId }
        .values
        .mapNotNull { rows ->
            val usableRows = rows.dropWhile { it.turn.role != ChatRole.USER }
            if (usableRows.isEmpty()) return@mapNotNull null

            ConversationInteraction(
                id = usableRows.first().interactionId,
                lastMessageId = usableRows.maxOf { it.messageId },
                createdAt = usableRows.minOf { it.createdAt },
                turns = usableRows.map { it.turn }
            )
        }
        .sortedBy { it.lastMessageId }
