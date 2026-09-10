package com.helltar.vusan.agent.conversation

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ConversationMessagesTable
import com.helltar.vusan.infra.tables.ConversationsTable
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.UserRef
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
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

        // the row may not exist yet: a conversation that has never been cleared has nothing to say
        // until its first recap, and the revision it starts at is the one a clear counts up from.
        ConversationsTable.upsert(
            onUpdate = {
                it[ConversationsTable.summary] = content
                it[ConversationsTable.summarizedThroughMessageId] = throughMessageId
            }
        ) {
            it[ConversationsTable.platform] = scope.platform
            it[ConversationsTable.userId] = scope.user.id
            it[ConversationsTable.chatId] = scope.chat.id
            it[ConversationsTable.revision] = 0L
            it[ConversationsTable.summary] = content
            it[ConversationsTable.summarizedThroughMessageId] = throughMessageId
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

    /**
     * The retention pass of [pruneCompacted] for conversations nobody has come back to. A turn prunes
     * its own conversation on the way out, so what is left for this is people who stopped writing —
     * their rows would otherwise sit past retention for as long as they stayed away.
     *
     * At most [maxConversations] of them per pass; whatever is still expired is taken by the next one.
     */
    suspend fun pruneExpired(
        maxStoredInteractions: Int,
        rawRetentionCutoff: Instant,
        maxConversations: Int
    ): Int {
        val expired =
            dbTransaction {
                ConversationMessagesTable
                    .select(
                        ConversationMessagesTable.platform,
                        ConversationMessagesTable.userId,
                        ConversationMessagesTable.chatId
                    )
                    .where { ConversationMessagesTable.createdAt less rawRetentionCutoff }
                    .withDistinct()
                    .limit(maxConversations)
                    .map {
                        val platform = it[ConversationMessagesTable.platform]

                        ConversationScope(
                            user = UserRef(platform, it[ConversationMessagesTable.userId]),
                            chat = ChatRef(platform, it[ConversationMessagesTable.chatId])
                        )
                    }
            }

        return expired.sumOf { pruneCompacted(it, maxStoredInteractions, rawRetentionCutoff) }
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
        ConversationsTable
            .select(ConversationsTable.revision)
            .where { conversationRowIs(scope) }
            .singleOrNull()
            ?.get(ConversationsTable.revision)
            ?: 0L
    }

    suspend fun clear(scope: ConversationScope) {
        dbTransaction {
            ConversationMessagesTable.deleteWhere { conversationIs(scope) }

            // the recap goes with the turns it recapped, and the revision counts the wipe: both in one
            // write, so nothing can come back to a summary of messages that are no longer there.
            ConversationsTable.upsert(
                onUpdate = {
                    it[ConversationsTable.revision] = ConversationsTable.revision + 1L
                    it[ConversationsTable.summary] = null
                    it[ConversationsTable.summarizedThroughMessageId] = 0L
                }
            ) {
                it[ConversationsTable.platform] = scope.platform
                it[ConversationsTable.userId] = scope.user.id
                it[ConversationsTable.chatId] = scope.chat.id
                it[ConversationsTable.revision] = 1L
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
        ConversationsTable
            .select(ConversationsTable.summary, ConversationsTable.summarizedThroughMessageId)
            .where { conversationRowIs(scope) }
            .singleOrNull()
            ?.let {
                StoredSummary(
                    content = it[ConversationsTable.summary],
                    throughMessageId = it[ConversationsTable.summarizedThroughMessageId]
                )
            }
            ?: StoredSummary(content = null, throughMessageId = 0L)

}

private fun conversationIs(scope: ConversationScope): Op<Boolean> =
    (ConversationMessagesTable.platform eq scope.platform) and
            (ConversationMessagesTable.userId eq scope.user.id) and
            (ConversationMessagesTable.chatId eq scope.chat.id)

private fun conversationRowIs(scope: ConversationScope): Op<Boolean> =
    (ConversationsTable.platform eq scope.platform) and
            (ConversationsTable.userId eq scope.user.id) and
            (ConversationsTable.chatId eq scope.chat.id)

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
