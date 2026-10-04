package com.helltar.vusan.agent.presence

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ChatDiaryTable
import com.helltar.vusan.request.ChatRef
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.LocalDate

/** What the bot wrote down about one day of one group. */
data class DiaryEntry(val day: LocalDate, val content: String)

/**
 * The bot's diary, one entry per chat per closed local day. A chat's entries are dropped together with
 * its transcript, by [com.helltar.vusan.agent.grouplog.GroupLogRepository.clear], since they were
 * written from it.
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

    /** Drops every chat's entries for days before [day]; answers how many went. */
    suspend fun pruneBefore(day: LocalDate): Int = dbTransaction {
        ChatDiaryTable.deleteWhere { ChatDiaryTable.day less day.toString() }
    }
}

private fun inChat(chat: ChatRef): Op<Boolean> =
    (ChatDiaryTable.platform eq chat.platform) and (ChatDiaryTable.chatId eq chat.id)
