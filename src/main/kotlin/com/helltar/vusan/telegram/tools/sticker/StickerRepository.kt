package com.helltar.vusan.telegram.tools.sticker

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.TelegramChatStickerSetsTable
import com.helltar.vusan.infra.tables.TelegramChatStickersTable
import com.helltar.vusan.infra.tables.TelegramStickerSetsTable
import com.helltar.vusan.infra.tables.TelegramStickersTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker
import java.time.Instant

/** A described sticker as stored, with the identity the usage rows join on. */
internal data class KnownSticker(val fileUniqueId: String, val entry: StickerEntry)

internal data class StickerUsage(val fileUniqueId: String, val seenCount: Int, val lastSeenAt: Instant)

internal data class PendingSticker(
    val id: Long,
    val fileId: String,
    val thumbnailFileId: String?,
    val describeAttempts: Int,
)

/**
 * The rows behind the sticker catalog: the sets pulled in, their stickers with the descriptions vision
 * gave them, and which chat used which. Telegram-owned like its tables, so a chat is its Telegram id here
 * and nothing takes a qualified reference.
 */
internal class StickerRepository {

    suspend fun recordStickerUsage(chatId: Long, fileUniqueId: String) {
        dbTransaction {
            val now = Instant.now()

            val updated =
                TelegramChatStickersTable.update({
                    (TelegramChatStickersTable.chatId eq chatId) and (TelegramChatStickersTable.fileUniqueId eq fileUniqueId)
                }) {
                    it[seenCount] = TelegramChatStickersTable.seenCount + 1
                    it[lastSeenAt] = now
                }

            if (updated == 0) {
                TelegramChatStickersTable.insert {
                    it[TelegramChatStickersTable.chatId] = chatId
                    it[TelegramChatStickersTable.fileUniqueId] = fileUniqueId
                    it[seenCount] = 1
                    it[lastSeenAt] = now
                }
            }
        }
    }

    /** Records this sighting and answers how many times the chat has now used the set. */
    suspend fun recordChatUsage(chatId: Long, setName: String): Int = dbTransaction {
        val now = Instant.now()

        val updated =
            TelegramChatStickerSetsTable.update({
                (TelegramChatStickerSetsTable.chatId eq chatId) and (TelegramChatStickerSetsTable.setName eq setName)
            }) {
                it[seenCount] = TelegramChatStickerSetsTable.seenCount + 1
                it[lastSeenAt] = now
            }

        if (updated == 0) {
            TelegramChatStickerSetsTable.insert {
                it[TelegramChatStickerSetsTable.chatId] = chatId
                it[TelegramChatStickerSetsTable.setName] = setName
                it[seenCount] = 1
                it[lastSeenAt] = now
            }

            return@dbTransaction 1
        }

        TelegramChatStickerSetsTable
            .select(TelegramChatStickerSetsTable.seenCount)
            .where { (TelegramChatStickerSetsTable.chatId eq chatId) and (TelegramChatStickerSetsTable.setName eq setName) }
            .single()[TelegramChatStickerSetsTable.seenCount]
    }

    suspend fun isSetStored(setName: String): Boolean = dbTransaction {
        TelegramStickerSetsTable
            .select(TelegramStickerSetsTable.name)
            .where { TelegramStickerSetsTable.name eq setName }
            .limit(1)
            .any()
    }

    /** How many sets were pulled in since [since], by [chatId] alone or by any chat. */
    suspend fun setsLearnedSince(since: Instant, chatId: Long? = null): Int = dbTransaction {
        var condition: Op<Boolean> = TelegramChatStickerSetsTable.learnedAt greater since

        chatId?.let { condition = condition and (TelegramChatStickerSetsTable.chatId eq it) }

        TelegramChatStickerSetsTable.selectAll().where { condition }.count().toInt()
    }

    suspend fun markLearnedIn(chatId: Long, setName: String) {
        dbTransaction {
            TelegramChatStickerSetsTable.update({
                (TelegramChatStickerSetsTable.chatId eq chatId) and (TelegramChatStickerSetsTable.setName eq setName)
            }) {
                it[learnedAt] = Instant.now()
            }
        }
    }

    /**
     * Brings the stored set in line with [live], the set as Telegram has it now: stickers gone from it are
     * dropped, handles that moved are updated, and of the stickers not held yet the first [maxStickers] of
     * the set are added. [live] is uncapped on purpose — what is stored has to be judged against the whole
     * set, or a sticker pushed past the cap by a reorder would look deleted and lose its description.
     */
    suspend fun syncSet(setName: String, live: List<Sticker>, maxStickers: Int) {
        dbTransaction {
            val liveByUniqueId = live.associateBy { it.fileUniqueId }

            val stored =
                TelegramStickersTable
                    .selectAll()
                    .where { TelegramStickersTable.setName eq setName }
                    .associate {
                        it[TelegramStickersTable.fileUniqueId] to
                                StoredHandles(it[TelegramStickersTable.fileId], it[TelegramStickersTable.thumbnailFileId])
                    }

            val gone = stored.keys - liveByUniqueId.keys

            if (gone.isNotEmpty()) {
                TelegramChatStickersTable.deleteWhere { TelegramChatStickersTable.fileUniqueId inList gone }
                TelegramStickersTable.deleteWhere {
                    (TelegramStickersTable.setName eq setName) and (TelegramStickersTable.fileUniqueId inList gone)
                }

                log.info { "dropped ${gone.size} sticker(s) removed from set=[$setName]" }
            }

            liveByUniqueId.forEach { (uniqueId, sticker) ->
                val handles = stored[uniqueId] ?: return@forEach
                if (handles.fileId == sticker.fileId && handles.thumbnailFileId == sticker.thumbnail?.fileId) return@forEach

                TelegramStickersTable.update({ TelegramStickersTable.fileUniqueId eq uniqueId }) {
                    it[fileId] = sticker.fileId
                    it[thumbnailFileId] = sticker.thumbnail?.fileId
                }
            }

            val fresh = live.take(maxStickers).filter { it.fileUniqueId !in stored }

            if (fresh.isNotEmpty()) {
                TelegramStickersTable.batchInsert(fresh) { sticker ->
                    this[TelegramStickersTable.fileUniqueId] = sticker.fileUniqueId
                    this[TelegramStickersTable.fileId] = sticker.fileId
                    this[TelegramStickersTable.setName] = setName
                    this[TelegramStickersTable.emoji] = sticker.emoji
                    this[TelegramStickersTable.thumbnailFileId] = sticker.thumbnail?.fileId
                }
            }

            markSetRefreshed(setName)
        }
    }

    suspend fun forgetSet(setName: String) {
        dbTransaction {
            val fileUniqueIds =
                TelegramStickersTable
                    .select(TelegramStickersTable.fileUniqueId)
                    .where { TelegramStickersTable.setName eq setName }
                    .map { it[TelegramStickersTable.fileUniqueId] }

            if (fileUniqueIds.isNotEmpty()) {
                TelegramChatStickersTable.deleteWhere { TelegramChatStickersTable.fileUniqueId inList fileUniqueIds }
            }

            TelegramStickersTable.deleteWhere { TelegramStickersTable.setName eq setName }
            TelegramChatStickerSetsTable.deleteWhere { TelegramChatStickerSetsTable.setName eq setName }
            TelegramStickerSetsTable.deleteWhere { TelegramStickerSetsTable.name eq setName }
        }
    }

    suspend fun markRefreshed(setName: String) {
        dbTransaction { markSetRefreshed(setName) }
    }

    /** Queues the set of [stickerId] for an early re-read and answers its name, `null` for an unknown sticker. */
    suspend fun queueRecheck(stickerId: Long): String? = dbTransaction {
        val setName =
            TelegramStickersTable
                .select(TelegramStickersTable.setName)
                .where { TelegramStickersTable.id eq stickerId }
                .firstOrNull()
                ?.get(TelegramStickersTable.setName)
                ?: return@dbTransaction null

        TelegramStickerSetsTable.update({ TelegramStickerSetsTable.name eq setName }) {
            it[refreshedAt] = Instant.EPOCH
        }

        setName
    }

    /** Up to [limit] sets last read before [before], the longest ago first. */
    suspend fun staleSetNames(before: Instant, limit: Int): List<String> = dbTransaction {
        TelegramStickerSetsTable
            .select(TelegramStickerSetsTable.name)
            .where { TelegramStickerSetsTable.refreshedAt less before }
            .orderBy(TelegramStickerSetsTable.refreshedAt to SortOrder.ASC)
            .limit(limit)
            .map { it[TelegramStickerSetsTable.name] }
    }

    /** The handle to send sticker [id] by, when it is described and from a set [chatId] has used. */
    suspend fun fileIdFor(chatId: Long, id: Long): String? = dbTransaction {
        val setNames = chatSetNames(chatId)
        if (setNames.isEmpty()) return@dbTransaction null

        TelegramStickersTable
            .select(TelegramStickersTable.fileId)
            .where {
                (TelegramStickersTable.id eq id) and
                        (TelegramStickersTable.setName inList setNames) and
                        TelegramStickersTable.description.isNotNull()
            }
            .firstOrNull()
            ?.get(TelegramStickersTable.fileId)
    }

    /** Every described sticker of the sets [chatId] has used, in the order they were learned. */
    suspend fun describedStickersFor(chatId: Long): List<KnownSticker> = dbTransaction {
        val setNames = chatSetNames(chatId)
        if (setNames.isEmpty()) return@dbTransaction emptyList()

        TelegramStickersTable
            .selectAll()
            .where { (TelegramStickersTable.setName inList setNames) and TelegramStickersTable.description.isNotNull() }
            .orderBy(TelegramStickersTable.id to SortOrder.ASC)
            .mapNotNull { row -> row.toKnownStickerOrNull() }
    }

    /** How often [chatId] reached for each of its sets. */
    suspend fun setWeightsFor(chatId: Long): Map<String, Int> = dbTransaction {
        TelegramChatStickerSetsTable
            .select(TelegramChatStickerSetsTable.setName, TelegramChatStickerSetsTable.seenCount)
            .where { TelegramChatStickerSetsTable.chatId eq chatId }
            .associate { it[TelegramChatStickerSetsTable.setName] to it[TelegramChatStickerSetsTable.seenCount] }
    }

    suspend fun stickerUsageFor(chatId: Long): List<StickerUsage> = dbTransaction {
        TelegramChatStickersTable
            .selectAll()
            .where { TelegramChatStickersTable.chatId eq chatId }
            .map { row ->
                StickerUsage(
                    fileUniqueId = row[TelegramChatStickersTable.fileUniqueId],
                    seenCount = row[TelegramChatStickersTable.seenCount],
                    lastSeenAt = row[TelegramChatStickersTable.lastSeenAt],
                )
            }
    }

    /** The next [limit] stickers without a description that have not been given up on, oldest first. */
    suspend fun pendingDescriptions(limit: Int): List<PendingSticker> = dbTransaction {
        TelegramStickersTable
            .selectAll()
            .where {
                TelegramStickersTable.description.isNull() and
                        (TelegramStickersTable.describeAttempts less MAX_DESCRIBE_ATTEMPTS)
            }
            .orderBy(TelegramStickersTable.id to SortOrder.ASC)
            .limit(limit)
            .map { row ->
                PendingSticker(
                    id = row[TelegramStickersTable.id].value,
                    fileId = row[TelegramStickersTable.fileId],
                    thumbnailFileId = row[TelegramStickersTable.thumbnailFileId],
                    describeAttempts = row[TelegramStickersTable.describeAttempts],
                )
            }
    }

    suspend fun storeDescription(id: Long, description: String) {
        dbTransaction {
            TelegramStickersTable.update({ TelegramStickersTable.id eq id }) {
                it[TelegramStickersTable.description] = description
            }
        }
    }

    /** Counts one more failed attempt on top of [attempts] and answers the new count. */
    suspend fun countDescribeAttempt(id: Long, attempts: Int): Int = dbTransaction {
        TelegramStickersTable.update({ TelegramStickersTable.id eq id }) {
            it[describeAttempts] = attempts + 1
        }

        attempts + 1
    }

    /** Takes the sticker out of the description queue for good. */
    suspend fun giveUpOnDescribing(id: Long) {
        dbTransaction {
            TelegramStickersTable.update({ TelegramStickersTable.id eq id }) {
                it[describeAttempts] = MAX_DESCRIBE_ATTEMPTS
            }
        }
    }

    private fun markSetRefreshed(setName: String) {
        val now = Instant.now()

        val updated =
            TelegramStickerSetsTable.update({ TelegramStickerSetsTable.name eq setName }) {
                it[refreshedAt] = now
            }

        if (updated == 0) {
            TelegramStickerSetsTable.insert {
                it[name] = setName
                it[refreshedAt] = now
            }
        }
    }

    private fun chatSetNames(chatId: Long): List<String> =
        TelegramChatStickerSetsTable
            .select(TelegramChatStickerSetsTable.setName)
            .where { TelegramChatStickerSetsTable.chatId eq chatId }
            .orderBy(TelegramChatStickerSetsTable.lastSeenAt to SortOrder.DESC)
            .map { it[TelegramChatStickerSetsTable.setName] }

    private fun ResultRow.toKnownStickerOrNull(): KnownSticker? =
        this[TelegramStickersTable.description]?.let { description ->
            KnownSticker(
                fileUniqueId = this[TelegramStickersTable.fileUniqueId],
                entry =
                    StickerEntry(
                        id = this[TelegramStickersTable.id].value,
                        setName = this[TelegramStickersTable.setName],
                        emoji = this[TelegramStickersTable.emoji],
                        description = description,
                    ),
            )
        }

    private data class StoredHandles(val fileId: String, val thumbnailFileId: String?)

    companion object {
        // people send stickers vision will not describe — explicit ones it refuses, and ones whose
        // download or call simply fails. one that failed this often leaves the queue, or it would stay at
        // the head forever and starve every sticker behind it.
        const val MAX_DESCRIBE_ATTEMPTS = 5

        private val log = KotlinLogging.logger {}
    }
}
