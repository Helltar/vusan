package com.helltar.vusan.telegram

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.TelegramWidgetVotesTable
import com.helltar.vusan.infra.tables.TelegramWidgetsTable
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.WidgetRequests
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.SentWidget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** A widget the bot sent, as the registry last wrote it. */
data class WidgetRecord(
    val chatId: Long,
    val messageId: Int,
    val threadId: Int?,
    val widgetId: String?,
    val requests: WidgetRequests,
    val caption: String,
    val chatData: String?,
    val publicData: String?,
    val revision: Int,
    val createdAt: Instant,
)

/** What a press in a counting widget came to: the published state, and this person's own choice. */
data class TallyOutcome(
    val record: WidgetRecord,
    val publicData: String,
    val mine: String?,
    val changed: Boolean,
    /** The choice could not be counted: one more option would not fit the 1024 bytes of shared state. */
    val refused: Boolean = false,
)

/**
 * What the bot's widgets are and show. A press carries the chat and message, or from a forwarded copy only
 * the shared widget's id; the Bot API reads no state back. So the purpose, the caption and every state the bot
 * wrote are kept here, and a counting widget's votes beside them.
 *
 * A shared widget's state is one for every message showing it, so its votes and published counts are kept
 * on the first message that showed it.
 */
class WidgetRegistry(private val retention: Duration = DEFAULT_RETENTION) {

    suspend fun remember(target: ChatTarget, sent: SentWidget, output: BotOutput) {
        val origin = (output as? BotOutput.SharedWidget)?.let { origin(it.widgetId) }

        val row =
            when (output) {
                is BotOutput.Widget ->
                    NewRow(output.requests, output.caption, output.chatData, output.publicData, revision = 0)

                is BotOutput.SharedWidget ->
                    NewRow(
                        requests = origin?.requests ?: WidgetRequests.NONE,
                        caption = output.caption,
                        chatData = output.chatData,
                        publicData = origin?.publicData,
                        revision = origin?.revision ?: 0,
                    )

                else -> return
            }

        dbTransaction {
            TelegramWidgetsTable.insertIgnore {
                it[chatId] = target.chatId
                it[messageId] = sent.messageId
                it[threadId] = target.messageThreadId
                it[widgetId] = sent.widgetId
                it[requests] = row.requests.name
                it[caption] = row.caption
                it[chatData] = row.chatData
                it[publicData] = row.publicData
                it[revision] = row.revision
            }
        }
    }

    suspend fun find(chatId: Long, messageId: Int): WidgetRecord? = dbTransaction { row(chatId, messageId) }

    /** The first message that showed shared widget [widgetId], which holds its votes and counts. */
    suspend fun origin(widgetId: String): WidgetRecord? = dbTransaction { originRow(widgetId) }

    /** This chat's latest widgets, newest first; in a forum, the topic's. */
    suspend fun recent(chatId: Long, threadId: Int?, limit: Int): List<WidgetRecord> = dbTransaction {
        TelegramWidgetsTable.selectAll()
            .where {
                val inChat = TelegramWidgetsTable.chatId eq chatId

                threadId?.let { inChat and (TelegramWidgetsTable.threadId eq it) } ?: inChat
            }
            // message ids grow within a chat, and two widgets can share a millisecond
            .orderBy(TelegramWidgetsTable.createdAt to SortOrder.DESC, TelegramWidgetsTable.messageId to SortOrder.DESC)
            .limit(limit)
            .map { it.toRecord() }
    }

    /**
     * Writes the state the bot just set on [record]: `null` keeps a field, an empty string clears it. The
     * shared state of a shared widget is every one of its messages', so all of them take it. Returns the new
     * revision.
     */
    suspend fun recordState(record: WidgetRecord, chatData: String?, publicData: String?): Int = dbTransaction {
        val revision = record.revision + 1

        TelegramWidgetsTable.update({ record.key() }) {
            chatData?.let { value -> it[TelegramWidgetsTable.chatData] = value.ifEmpty { null } }
            publicData?.let { value -> it[TelegramWidgetsTable.publicData] = value.ifEmpty { null } }
            it[TelegramWidgetsTable.revision] = revision
        }

        if (publicData != null && record.widgetId != null) sharePublicData(record.widgetId, publicData.ifEmpty { null }, revision)

        revision
    }

    /**
     * Counts [userId]'s press of [choice] in counting widget [record]: a new choice replaces their last one,
     * the same choice again takes it back, and [TALLY_READ] only reads. The counts are published as the
     * shared state; a choice that would not fit is refused and nothing changes.
     */
    suspend fun tally(record: WidgetRecord, userId: Long, choice: String): TallyOutcome = dbTransaction {
        // read inside the transaction: the record a press was matched with may be a vote behind already
        val holder = (record.widgetId?.let { originRow(it) } ?: row(record.chatId, record.messageId)) ?: record

        val votes =
            TelegramWidgetVotesTable.selectAll()
                .where { holder.votesKey() }
                .associate { it[TelegramWidgetVotesTable.userId] to it[TelegramWidgetVotesTable.choice] }

        val current = votes[userId]
        val next = if (choice == TALLY_READ) current else choice.takeUnless { it == current }
        val updated = if (next == null) votes - userId else votes + (userId to next)

        val publicData = tallyPublicData(holder.revision + 1, updated.values)

        when {
            choice == TALLY_READ || next == current ->
                TallyOutcome(holder, holder.publicData ?: tallyPublicData(holder.revision, votes.values), current, changed = false)

            publicData.toByteArray().size > MAX_TALLY_PUBLIC_BYTES ->
                TallyOutcome(holder, holder.publicData ?: tallyPublicData(holder.revision, votes.values), current, changed = false, refused = true)

            else -> {
                if (next == null) {
                    TelegramWidgetVotesTable.deleteWhere { holder.votesKey() and (TelegramWidgetVotesTable.userId eq userId) }
                } else {
                    TelegramWidgetVotesTable.upsert(onUpdate = { it[TelegramWidgetVotesTable.choice] = next }) {
                        it[chatId] = holder.chatId
                        it[messageId] = holder.messageId
                        it[TelegramWidgetVotesTable.userId] = userId
                        it[TelegramWidgetVotesTable.choice] = next
                    }
                }

                val revision = holder.revision + 1

                TelegramWidgetsTable.update({ holder.key() }) {
                    it[TelegramWidgetsTable.publicData] = publicData
                    it[TelegramWidgetsTable.revision] = revision
                }

                holder.widgetId?.let { sharePublicData(it, publicData, revision) }

                TallyOutcome(holder.copy(publicData = publicData, revision = revision), publicData, next, changed = true)
            }
        }
    }

    /** Drops widgets past [retention] at [now], with their votes, and returns how many. */
    suspend fun pruneExpired(now: Instant): Int = dbTransaction {
        val cutoff = now.minusMillis(retention.inWholeMilliseconds)

        val expired =
            TelegramWidgetsTable.selectAll()
                .where { TelegramWidgetsTable.createdAt less cutoff }
                .map { it[TelegramWidgetsTable.chatId] to it[TelegramWidgetsTable.messageId] }

        expired.forEach { (chat, message) ->
            TelegramWidgetVotesTable.deleteWhere {
                (TelegramWidgetVotesTable.chatId eq chat) and (TelegramWidgetVotesTable.messageId eq message)
            }
        }

        TelegramWidgetsTable.deleteWhere { TelegramWidgetsTable.createdAt less cutoff }
    }

    private fun JdbcTransaction.row(chatId: Long, messageId: Int): WidgetRecord? =
        TelegramWidgetsTable.selectAll()
            .where { (TelegramWidgetsTable.chatId eq chatId) and (TelegramWidgetsTable.messageId eq messageId) }
            .singleOrNull()
            ?.toRecord()

    private fun JdbcTransaction.originRow(widgetId: String): WidgetRecord? =
        TelegramWidgetsTable.selectAll()
            .where { TelegramWidgetsTable.widgetId eq widgetId }
            .orderBy(TelegramWidgetsTable.createdAt to SortOrder.ASC, TelegramWidgetsTable.messageId to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.toRecord()

    private fun sharePublicData(widgetId: String, publicData: String?, revision: Int) {
        TelegramWidgetsTable.update({ TelegramWidgetsTable.widgetId eq widgetId }) {
            it[TelegramWidgetsTable.publicData] = publicData
            it[TelegramWidgetsTable.revision] = revision
        }
    }

    private class NewRow(
        val requests: WidgetRequests,
        val caption: String,
        val chatData: String?,
        val publicData: String?,
        val revision: Int,
    )

    companion object {
        /** The press a counting widget makes to learn the counts and its viewer's own choice without voting. */
        const val TALLY_READ = "?"

        // the shared state takes 1024 bytes, and the answer adds the person's own choice to the same counts
        private const val MAX_TALLY_PUBLIC_BYTES = 900

        // long enough for a game or a sign-up that runs a season, short enough not to keep every card ever drawn
        private val DEFAULT_RETENTION = 90.days

        private fun WidgetRecord.key() =
            (TelegramWidgetsTable.chatId eq chatId) and (TelegramWidgetsTable.messageId eq messageId)

        private fun WidgetRecord.votesKey() =
            (TelegramWidgetVotesTable.chatId eq chatId) and (TelegramWidgetVotesTable.messageId eq messageId)

        private fun ResultRow.toRecord(): WidgetRecord =
            WidgetRecord(
                chatId = this[TelegramWidgetsTable.chatId],
                messageId = this[TelegramWidgetsTable.messageId],
                threadId = this[TelegramWidgetsTable.threadId],
                widgetId = this[TelegramWidgetsTable.widgetId],
                requests = WidgetRequests.entries.firstOrNull { it.name == this[TelegramWidgetsTable.requests] } ?: WidgetRequests.NONE,
                caption = this[TelegramWidgetsTable.caption],
                chatData = this[TelegramWidgetsTable.chatData],
                publicData = this[TelegramWidgetsTable.publicData],
                revision = this[TelegramWidgetsTable.revision],
                createdAt = this[TelegramWidgetsTable.createdAt],
            )
    }
}

/** A counting widget's shared state: `{"rev":3,"counts":{"yes":2,"no":1},"total":3}`, the largest count first. */
internal fun tallyPublicData(revision: Int, choices: Collection<String>): String {
    val counts = choices.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }

    return buildJsonObject {
        put("rev", revision)
        putJsonObject("counts") { counts.forEach { (choice, count) -> put(choice, count) } }
        put("total", choices.size)
    }.toString()
}

/**
 * The answer a press in a counting widget gets: the shared state with the presser's own choice added
 * (`"mine"`, `null` without one) and `"refused":true` when their choice did not fit.
 */
internal fun tallyAnswer(publicData: String, mine: String?, refused: Boolean): String {
    val state = runCatching { Json.parseToJsonElement(publicData).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    return buildJsonObject {
        state.forEach { (key, value) -> put(key, value) }
        put("mine", mine)
        if (refused) put("refused", true)
    }.toString()
}
