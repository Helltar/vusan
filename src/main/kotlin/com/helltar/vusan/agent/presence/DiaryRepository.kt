package com.helltar.vusan.agent.presence

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ChatDiaryLookBackTable
import com.helltar.vusan.infra.tables.ChatDiaryTable
import com.helltar.vusan.request.ChatRef
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.LocalDate

/** What the bot wrote down about one day of one group. */
data class DiaryEntry(val day: LocalDate, val content: String)

/** How long a span of days a look-back covers. */
enum class DiaryPeriod {
    /** Monday to Sunday. */
    WEEK,
    MONTH,
}

/** One closed week or month, named by its period and its first local day. */
data class DiarySpan(val period: DiaryPeriod, val firstDay: LocalDate) {

    val lastDay: LocalDate
        get() =
            when (period) {
                DiaryPeriod.WEEK -> firstDay.plusDays(6)
                DiaryPeriod.MONTH -> firstDay.plusMonths(1).minusDays(1)
            }
}

/** What the bot wrote looking back over one week or month of one group, from that span's daily entries. */
data class DiaryLookBack(val span: DiarySpan, val content: String)

/**
 * The bot's diary: one entry per chat per closed local day, and one look-back per closed week and month.
 * A chat's diary is dropped together with its transcript, by
 * [com.helltar.vusan.agent.grouplog.GroupLogRepository.clear], since it was written from it.
 */
class DiaryRepository {

    suspend fun has(chat: ChatRef, day: LocalDate): Boolean = dbTransaction {
        ChatDiaryTable
            .select(ChatDiaryTable.day)
            .where { inChat(chat) and (ChatDiaryTable.day eq day.toString()) }
            .any()
    }

    /** The entries for [since] and later, oldest first. */
    suspend fun since(chat: ChatRef, since: LocalDate): List<DiaryEntry> = dbTransaction {
        ChatDiaryTable
            .selectAll()
            // `yyyy-MM-dd` sorts the way the dates do, which is what lets a text column be compared
            .where { inChat(chat) and (ChatDiaryTable.day greaterEq since.toString()) }
            .orderBy(ChatDiaryTable.day to SortOrder.ASC)
            .map { DiaryEntry(LocalDate.parse(it[ChatDiaryTable.day]), it[ChatDiaryTable.content]) }
    }

    /** The entries from [from] to [to], both included, oldest first. */
    suspend fun between(chat: ChatRef, from: LocalDate, to: LocalDate): List<DiaryEntry> = dbTransaction {
        ChatDiaryTable
            .selectAll()
            .where {
                inChat(chat) and (ChatDiaryTable.day greaterEq from.toString()) and (ChatDiaryTable.day lessEq to.toString())
            }
            .orderBy(ChatDiaryTable.day to SortOrder.ASC)
            .map { DiaryEntry(LocalDate.parse(it[ChatDiaryTable.day]), it[ChatDiaryTable.content]) }
    }

    /** The chats with at least one entry from [from] to [to], both included. */
    suspend fun chatsWithEntries(from: LocalDate, to: LocalDate): List<ChatRef> = dbTransaction {
        ChatDiaryTable
            .select(ChatDiaryTable.platform, ChatDiaryTable.chatId)
            .where { (ChatDiaryTable.day greaterEq from.toString()) and (ChatDiaryTable.day lessEq to.toString()) }
            .withDistinct()
            .map { ChatRef(it[ChatDiaryTable.platform], it[ChatDiaryTable.chatId]) }
    }

    suspend fun store(chat: ChatRef, entry: DiaryEntry) {
        require(entry.content.isNotBlank()) { "A diary entry must not be blank" }

        dbTransaction {
            ChatDiaryTable.upsert(
                onUpdate = { it[ChatDiaryTable.content] = entry.content },
            ) {
                it[platform] = chat.platform
                it[chatId] = chat.id
                it[day] = entry.day.toString()
                it[content] = entry.content
            }
        }
    }

    suspend fun hasLookBack(chat: ChatRef, span: DiarySpan): Boolean = dbTransaction {
        ChatDiaryLookBackTable
            .select(ChatDiaryLookBackTable.firstDay)
            .where { lookBacksInChat(chat, span.period) and (ChatDiaryLookBackTable.firstDay eq span.firstDay.toString()) }
            .any()
    }

    /** The newest look-back over a [period] that starts before [before]. */
    suspend fun latestLookBack(chat: ChatRef, period: DiaryPeriod, before: LocalDate): DiaryLookBack? = dbTransaction {
        ChatDiaryLookBackTable
            .selectAll()
            .where { lookBacksInChat(chat, period) and (ChatDiaryLookBackTable.firstDay less before.toString()) }
            .orderBy(ChatDiaryLookBackTable.firstDay to SortOrder.DESC)
            .limit(1)
            .map {
                DiaryLookBack(
                    DiarySpan(period, LocalDate.parse(it[ChatDiaryLookBackTable.firstDay])),
                    it[ChatDiaryLookBackTable.content],
                )
            }
            .firstOrNull()
    }

    suspend fun storeLookBack(chat: ChatRef, lookBack: DiaryLookBack) {
        require(lookBack.content.isNotBlank()) { "A diary look-back must not be blank" }

        dbTransaction {
            ChatDiaryLookBackTable.upsert(
                onUpdate = { it[ChatDiaryLookBackTable.content] = lookBack.content },
            ) {
                it[platform] = chat.platform
                it[chatId] = chat.id
                it[period] = lookBack.span.period.name.lowercase()
                it[firstDay] = lookBack.span.firstDay.toString()
                it[content] = lookBack.content
            }
        }
    }
}

private fun lookBacksInChat(chat: ChatRef, period: DiaryPeriod): Op<Boolean> =
    (ChatDiaryLookBackTable.platform eq chat.platform) and (ChatDiaryLookBackTable.chatId eq chat.id) and
            (ChatDiaryLookBackTable.period eq period.name.lowercase())

private fun inChat(chat: ChatRef): Op<Boolean> =
    (ChatDiaryTable.platform eq chat.platform) and (ChatDiaryTable.chatId eq chat.id)
