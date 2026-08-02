package com.helltar.vusan.tools.sticker

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ChatStickerSetsTable
import com.helltar.vusan.infra.tables.StickerSetsTable
import com.helltar.vusan.infra.tables.StickersTable
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.delivery.isStickerSetGone
import com.helltar.vusan.telegram.downloadFileBytes
import com.helltar.vusan.tools.vision.EMPTY_VISION_DESCRIPTION
import com.helltar.vusan.tools.vision.ImageVisionClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
import org.telegram.telegrambots.meta.api.methods.stickers.GetStickerSet
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val REGULAR_STICKER = "regular"

// one member throwing a sticker unlocks its whole set, which can hold 120 of them. describing every
// one costs a vision call, so only the front of a set is taken — enough to speak the set's language
// without paying for its long tail.
private const val MAX_STICKERS_PER_SET = 60

// how much of the catalog the model is shown on a turn. the catalog itself grows without limit as
// people keep sending stickers; the prompt cannot, so the index stays bounded by the sets a chat
// actually uses and, within them, by a hard entry count.
private const val MAX_INDEX_SETS = 3
private const val MAX_INDEX_ENTRIES = 80
private const val MAX_DESCRIPTION_CHARS = 90

private const val DESCRIPTIONS_PER_PASS = 20
private const val MAX_DESCRIBE_ATTEMPTS = 5
private const val SETS_PER_REFRESH_PASS = 3
private val DESCRIPTION_PAUSE = 300.milliseconds
private val BACKLOG_POLL_INTERVAL = 60.seconds

// sticker sets change rarely, and re-reading one costs an API call, so this only has to be often
// enough that a deleted set stops being offered within a day.
private val SET_REFRESH_INTERVAL = 24.hours

private const val SKIP_SENTINEL = "SKIP"

private const val STICKER_VISION_FOCUS =
    "This image is a Telegram sticker, used in chat the way a reaction or a punchline is. " +
            "In at most 12 words, name who or what is shown, what they are doing or feeling, and the mood. " +
            "Answer in English with that phrase alone — no preamble, no full sentence, no mention of it being a sticker. " +
            "If you will not describe it for any reason, answer with the single word `$SKIP_SENTINEL` and nothing else."

// a vision model asked about an explicit or otherwise unwelcome sticker answers in prose instead of
// erroring, and that prose must never be stored as a catalog entry.
private val REFUSAL_REGEX =
    Regex(
        "^(i'?m sorry|i am sorry|sorry\\b|i can'?t|i cannot|i'?m unable|i am unable|unable to|" +
                "i won'?t|i will not|cannot assist|can'?t assist|cannot help|can'?t help)",
        RegexOption.IGNORE_CASE
    )

/**
 * Take from each source in turn until [limit] is reached, so a long source cannot crowd out a short one.
 * Sources are drawn in the order given, and one that runs out simply drops out of the rotation.
 */
internal fun <T> roundRobin(sources: List<List<T>>, limit: Int): List<T> {
    require(limit >= 0) { "limit must not be negative" }

    val queues = sources.map { it.iterator() }.filter { it.hasNext() }

    return buildList {
        while (size < limit) {
            val before = size

            for (queue in queues) {
                if (size >= limit) break
                if (queue.hasNext()) add(queue.next())
            }

            // every source is exhausted, so the result is simply smaller than the limit
            if (size == before) break
        }
    }
}

/**
 * The stickers this bot knows how to send, learned from the ones people actually use.
 *
 * A sticker seen in an allowlisted chat reveals its set, the set is pulled in whole, and each of its
 * stickers is described once by vision so the model can pick by meaning rather than by emoji. The
 * catalog is global, the index offered per chat.
 */
class StickerCatalog(
    private val client: TelegramClient,
    private val vision: ImageVisionClient
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    data class StickerEntry(val id: Long, val setName: String, val emoji: String?, val description: String)

    /** Record a sticker seen in a chat, pulling in its set the first time that set shows up. */
    suspend fun observe(chatId: Long, sticker: Sticker) {
        val setName = sticker.setName?.takeIf { it.isNotBlank() } ?: return
        if (sticker.type != null && sticker.type != REGULAR_STICKER) return

        runCatching {
            recordChatUsage(chatId, setName)

            if (!isSetStored(setName)) {
                val stickers = fetchSet(setName)
                syncSet(setName, stickers)
                log.info { "learned sticker set=[$setName] (${stickers.size} stickers)" }
            }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to learn sticker set=[$setName] from chat=$chatId" }
        }
    }

    /**
     * The sticker index for this chat's system context, or `null` when nothing is described yet —
     * which is the normal state of a fresh deployment and of a chat where nobody uses stickers.
     */
    suspend fun indexBlockFor(chatId: Long): String? {
        val entries = describedEntriesFor(chatId)
        if (entries.isEmpty()) return null

        return xmlBlock(
            "sticker_catalog",
            entries.joinToString("\n") { entry ->
                buildString {
                    append('#').append(entry.id).append(' ')
                    entry.emoji?.let { append(it).append(' ') }
                    append(entry.description)
                }
            }
        )
    }

    suspend fun fileIdFor(id: Long): String? = dbTransaction {
        StickersTable
            .select(StickersTable.fileId)
            .where { StickersTable.id eq id }
            .firstOrNull()
            ?.get(StickersTable.fileId)
    }

    /**
     * Telegram rejected this sticker's `file_id`. Nothing is deleted on that alone — a send fails for
     * plenty of reasons that say nothing about the sticker — so the set is only marked for an early
     * re-read, and [refreshStaleSets] decides from Telegram's own answer what to drop.
     */
    suspend fun recheckSetOf(stickerId: Long) = dbTransaction {
        val setName =
            StickersTable
                .select(StickersTable.setName)
                .where { StickersTable.id eq stickerId }
                .firstOrNull()
                ?.get(StickersTable.setName)
                ?: return@dbTransaction

        StickerSetsTable.update({ StickerSetsTable.name eq setName }) {
            it[refreshedAt] = Instant.EPOCH
        }

        log.info { "sticker id=$stickerId was rejected; set=[$setName] queued for an early re-read" }
    }

    /**
     * Describe newly learned stickers in the background. Vision failures are expected and survivable —
     * an undescribed sticker simply stays out of the index and is retried on a later pass.
     */
    fun launchDescriptionWorker(scope: CoroutineScope): Job =
        scope.launch {
            log.info { "sticker description worker started" }

            while (isActive) {
                runCatching { describePending() }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.warn(it) { "sticker description pass failed" }
                    }

                runCatching { refreshStaleSets() }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.warn(it) { "sticker set refresh pass failed" }
                    }

                delay(BACKLOG_POLL_INTERVAL)
            }
        }

    /**
     * Re-read the sets learned longest ago. A `file_id` is only a handle, and a set's owner can add to
     * it, remove from it, or delete it outright, so what the catalog offers the model has to be checked
     * against Telegram now and then — otherwise the bot keeps proposing stickers whose send will fail.
     */
    private suspend fun refreshStaleSets() {
        for (setName in staleSetNames()) {
            val stickers =
                runCatching { fetchSet(setName) }
                    .onFailure { error ->
                        error.rethrowIfCancellation()

                        if (error.isStickerSetGone()) {
                            forgetSet(setName)
                            log.info { "sticker set=[$setName] no longer exists; dropped from the catalog" }
                        } else {
                            // a transient failure is no reason to throw away described stickers; back off
                            // instead of retrying it on every poll.
                            log.warn { "could not refresh sticker set=[$setName]: ${error.message}" }
                            markRefreshed(setName)
                        }
                    }
                    .getOrNull()
                    ?: continue

            syncSet(setName, stickers)
        }
    }

    private suspend fun describePending() {
        val pending = pendingDescriptions()
        if (pending.isEmpty()) return

        log.info { "describing ${pending.size} sticker(s)" }

        for (row in pending) {
            when (val outcome = describe(row)) {
                is DescribeOutcome.Described -> storeDescription(row.id, outcome.text)

                // a refusal is a verdict, not a hiccup: asking again would only spend another call.
                is DescribeOutcome.Refused -> {
                    log.info { "sticker id=${row.id} left out of the catalog: vision would not describe it" }
                    giveUpOnDescribing(row.id)
                }

                is DescribeOutcome.Failed -> countFailedAttempt(row.id, row.describeAttempts)
            }

            delay(DESCRIPTION_PAUSE)
        }
    }

    private suspend fun describe(row: PendingSticker): DescribeOutcome {
        val sourceFileId = row.thumbnailFileId ?: row.fileId

        val bytes =
            runCatching { client.downloadFileBytes(sourceFileId) }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.warn { "failed to download sticker id=${row.id} for description: ${it.message}" }
                }
                .getOrNull()
                ?: return DescribeOutcome.Failed

        val image =
            AttachedFile(
                name = "sticker.webp",
                fileSizeBytes = bytes.size.toLong(),
                mimeType = "image/webp",
                kind = AttachedFileKind.IMAGE,
                loadBytes = { bytes }
            )

        val answer =
            runCatching { vision.describe(image, bytes, STICKER_VISION_FOCUS) }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.warn { "vision call failed for sticker id=${row.id}: ${it.message}" }
                }
                .getOrNull()
                ?: return DescribeOutcome.Failed

        val text = answer.collapseWhitespaceAndCap(MAX_DESCRIPTION_CHARS).orEmpty()

        return if (text.isUsableDescription()) DescribeOutcome.Described(text) else DescribeOutcome.Refused
    }

    private fun String.isUsableDescription(): Boolean =
        isNotBlank() &&
                !startsWith(SKIP_SENTINEL, ignoreCase = true) &&
                !equals(EMPTY_VISION_DESCRIPTION, ignoreCase = true) &&
                !REFUSAL_REGEX.containsMatchIn(this)

    private suspend fun fetchSet(setName: String): List<Sticker> =
        client.api { executeAsync(GetStickerSet.builder().name(setName).build()) }
            .stickers
            .orEmpty()
            .filter { it.type == null || it.type == REGULAR_STICKER }

    // [live] is the set as Telegram has it now, uncapped: what is stored has to be judged against the
    // whole set, or a sticker pushed past the cap by a reorder would look deleted and lose its description.
    private suspend fun syncSet(setName: String, live: List<Sticker>) = dbTransaction {
        val liveByUniqueId = live.associateBy { it.fileUniqueId }

        val stored =
            StickersTable
                .selectAll()
                .where { StickersTable.setName eq setName }
                .associate {
                    it[StickersTable.fileUniqueId] to
                            StoredHandles(it[StickersTable.fileId], it[StickersTable.thumbnailFileId])
                }

        val gone = stored.keys - liveByUniqueId.keys

        if (gone.isNotEmpty()) {
            StickersTable.deleteWhere {
                (StickersTable.setName eq setName) and (StickersTable.fileUniqueId inList gone)
            }

            log.info { "dropped ${gone.size} sticker(s) removed from set=[$setName]" }
        }

        liveByUniqueId.forEach { (uniqueId, sticker) ->
            val handles = stored[uniqueId] ?: return@forEach
            if (handles.fileId == sticker.fileId && handles.thumbnailFileId == sticker.thumbnail?.fileId) return@forEach

            StickersTable.update({ StickersTable.fileUniqueId eq uniqueId }) {
                it[fileId] = sticker.fileId
                it[thumbnailFileId] = sticker.thumbnail?.fileId
            }
        }

        val fresh = live.take(MAX_STICKERS_PER_SET).filter { it.fileUniqueId !in stored }

        if (fresh.isNotEmpty()) {
            StickersTable.batchInsert(fresh) { sticker ->
                this[StickersTable.fileUniqueId] = sticker.fileUniqueId
                this[StickersTable.fileId] = sticker.fileId
                this[StickersTable.setName] = setName
                this[StickersTable.emoji] = sticker.emoji
                this[StickersTable.thumbnailFileId] = sticker.thumbnail?.fileId
            }
        }

        markSetRefreshed(setName)
    }

    private suspend fun forgetSet(setName: String) = dbTransaction {
        StickersTable.deleteWhere { StickersTable.setName eq setName }
        ChatStickerSetsTable.deleteWhere { ChatStickerSetsTable.setName eq setName }
        StickerSetsTable.deleteWhere { StickerSetsTable.name eq setName }
        Unit
    }

    private suspend fun markRefreshed(setName: String) = dbTransaction { markSetRefreshed(setName) }

    private fun markSetRefreshed(setName: String) {
        val now = Instant.now()

        val updated =
            StickerSetsTable.update({ StickerSetsTable.name eq setName }) {
                it[refreshedAt] = now
            }

        if (updated == 0) {
            StickerSetsTable.insert {
                it[name] = setName
                it[refreshedAt] = now
            }
        }
    }

    private suspend fun staleSetNames(): List<String> = dbTransaction {
        StickerSetsTable
            .select(StickerSetsTable.name)
            .where { StickerSetsTable.refreshedAt less Instant.now().minusSeconds(SET_REFRESH_INTERVAL.inWholeSeconds) }
            .orderBy(StickerSetsTable.refreshedAt to SortOrder.ASC)
            .limit(SETS_PER_REFRESH_PASS)
            .map { it[StickerSetsTable.name] }
    }

    private suspend fun isSetStored(setName: String): Boolean = dbTransaction {
        StickerSetsTable
            .select(StickerSetsTable.id)
            .where { StickerSetsTable.name eq setName }
            .limit(1)
            .any()
    }

    private data class StoredHandles(val fileId: String, val thumbnailFileId: String?)

    private suspend fun recordChatUsage(chatId: Long, setName: String) = dbTransaction {
        val now = Instant.now()

        val updated =
            ChatStickerSetsTable.update({
                (ChatStickerSetsTable.chatId eq chatId) and (ChatStickerSetsTable.setName eq setName)
            }) {
                it[seenCount] = ChatStickerSetsTable.seenCount + 1
                it[lastSeenAt] = now
            }

        if (updated == 0) {
            ChatStickerSetsTable.insert {
                it[ChatStickerSetsTable.chatId] = chatId
                it[ChatStickerSetsTable.setName] = setName
                it[seenCount] = 1
                it[lastSeenAt] = now
            }
        }
    }

    private suspend fun describedEntriesFor(chatId: Long): List<StickerEntry> = dbTransaction {
        val setNames =
            ChatStickerSetsTable
                .select(ChatStickerSetsTable.setName)
                .where { ChatStickerSetsTable.chatId eq chatId }
                .orderBy(ChatStickerSetsTable.lastSeenAt to SortOrder.DESC)
                .limit(MAX_INDEX_SETS)
                .map { it[ChatStickerSetsTable.setName] }

        if (setNames.isEmpty()) return@dbTransaction emptyList()

        val bySet =
            StickersTable
                .selectAll()
                .where { (StickersTable.setName inList setNames) and StickersTable.description.isNotNull() }
                .orderBy(StickersTable.id to SortOrder.ASC)
                .mapNotNull { row -> row.toStickerEntryOrNull() }
                .groupBy { it.setName }

        roundRobin(setNames.map { bySet[it].orEmpty() }, MAX_INDEX_ENTRIES).sortedBy { it.id }
    }

    private fun ResultRow.toStickerEntryOrNull(): StickerEntry? =
        this[StickersTable.description]?.let { description ->
            StickerEntry(
                id = this[StickersTable.id].value,
                setName = this[StickersTable.setName],
                emoji = this[StickersTable.emoji],
                description = description
            )
        }

    private suspend fun pendingDescriptions(): List<PendingSticker> = dbTransaction {
        StickersTable
            .selectAll()
            .where {
                StickersTable.description.isNull() and
                        (StickersTable.describeAttempts less MAX_DESCRIBE_ATTEMPTS)
            }
            .orderBy(StickersTable.id to SortOrder.ASC)
            .limit(DESCRIPTIONS_PER_PASS)
            .map { row ->
                PendingSticker(
                    id = row[StickersTable.id].value,
                    fileId = row[StickersTable.fileId],
                    thumbnailFileId = row[StickersTable.thumbnailFileId],
                    describeAttempts = row[StickersTable.describeAttempts]
                )
            }
    }

    private suspend fun storeDescription(id: Long, description: String) = dbTransaction {
        StickersTable.update({ StickersTable.id eq id }) {
            it[StickersTable.description] = description
        }
    }

    private suspend fun countFailedAttempt(id: Long, attempts: Int) {
        dbTransaction {
            StickersTable.update({ StickersTable.id eq id }) {
                it[describeAttempts] = attempts + 1
            }
        }

        if (attempts + 1 >= MAX_DESCRIBE_ATTEMPTS) {
            log.warn { "giving up on describing sticker id=$id after $MAX_DESCRIBE_ATTEMPTS attempts" }
        }
    }

    private suspend fun giveUpOnDescribing(id: Long) = dbTransaction {
        StickersTable.update({ StickersTable.id eq id }) {
            it[describeAttempts] = MAX_DESCRIBE_ATTEMPTS
        }
    }

    private data class PendingSticker(
        val id: Long,
        val fileId: String,
        val thumbnailFileId: String?,
        val describeAttempts: Int
    )

    private sealed interface DescribeOutcome {
        data class Described(val text: String) : DescribeOutcome
        data object Refused : DescribeOutcome
        data object Failed : DescribeOutcome
    }
}
