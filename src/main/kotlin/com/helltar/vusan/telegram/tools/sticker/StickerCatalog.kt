package com.helltar.vusan.telegram.tools.sticker

import com.helltar.vusan.agent.neutralizePromptBlocks
import com.helltar.vusan.budget.tokenBudgetStop
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.TelegramChatStickerSetsTable
import com.helltar.vusan.infra.tables.TelegramChatStickersTable
import com.helltar.vusan.infra.tables.TelegramStickerSetsTable
import com.helltar.vusan.infra.tables.TelegramStickersTable
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.telegramChatId
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
import org.telegram.telegrambots.meta.api.methods.stickers.GetStickerSet
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val REGULAR_STICKER = "regular"

// one member throwing a sticker unlocks its whole set, which can hold 120 of them. describing every
// one costs a vision call, so only the front of a set is taken — enough to speak the set's language
// without paying for its long tail.
private const val MAX_STICKERS_PER_SET = 60

// the prompt keeps a small ready-to-send selection; the search tool reaches the full chat catalog
// when none of these fits. half of the shortlist is reserved for recent individual stickers, then
// frequent ones and a round-robin fallback across known sets fill what remains.
private const val MAX_INDEX_ENTRIES = 16
private const val RECENT_INDEX_ENTRIES = MAX_INDEX_ENTRIES / 2
private const val MAX_DESCRIPTION_CHARS = 90

// pulling in a set is the only expensive thing here — up to MAX_STICKERS_PER_SET vision calls, paid
// once. these two keep that bill tied to what a chat actually uses: a set nobody reaches for twice is
// never learned, and no chat can pull in more than a handful of new sets a day however many stickers
// it throws. neither applies to a set already known from another chat, which costs nothing to offer.
private const val MIN_USES_BEFORE_LEARNING = 2
private const val MAX_NEW_SETS_PER_CHAT_PER_DAY = 3
private val NEW_SET_BUDGET_WINDOW = 24.hours

private const val DESCRIPTIONS_PER_PASS = 20
private const val MAX_DESCRIBE_ATTEMPTS = 5
private const val SETS_PER_REFRESH_PASS = 3
private val DESCRIPTION_PAUSE = 300.milliseconds
private val BACKLOG_POLL_INTERVAL = 60.seconds

// sticker sets change rarely, and re-reading one costs an API call, so this only has to be often
// enough that a deleted set stops being offered within a day.
private val SET_REFRESH_INTERVAL = 24.hours

private const val SKIP_SENTINEL = "SKIP"

private val SEARCH_WORD_REGEX = Regex("[\\p{L}\\p{N}]+")
private val SEARCH_WHITESPACE_REGEX = Regex("\\s+")

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

internal data class StickerEntry(val id: Long, val setName: String, val emoji: String?, val description: String)

// the description is written from an image somebody else sent into the chat, and the line lands both
// in `<sticker_catalog>` and in a tool result, so it is defused once here for both.
internal fun StickerEntry.catalogLine(): String =
    buildString {
        append('#').append(id).append(' ')
        emoji?.let { append(it).append(' ') }
        append(description)
    }.neutralizePromptBlocks()

private data class SearchMatch(val entry: StickerEntry, val score: Int)

private fun String.matchesSearchWord(queryWord: String): Boolean =
    this == queryWord ||
            (length >= 4 && queryWord.length >= 4 && (startsWith(queryWord) || queryWord.startsWith(this)))

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

    /** Record a sticker seen in a chat, pulling in its set once that set has earned it. */
    suspend fun observe(chatId: Long, sticker: Sticker) {
        val setName = sticker.setName?.takeIf { it.isNotBlank() } ?: return
        if (sticker.type != null && sticker.type != REGULAR_STICKER) return

        runCatching {
            recordStickerUsage(chatId, sticker.fileUniqueId)
            val seenCount = recordChatUsage(chatId, setName)

            // a set already known from anywhere is free to offer here: no fetch, no vision, so neither
            // of the cost guards below applies to it.
            if (isSetStored(setName)) return@runCatching

            if (seenCount < MIN_USES_BEFORE_LEARNING) {
                log.debug { "sticker set=[$setName] seen $seenCount time(s) in chat=$chatId; not learning it yet" }
                return@runCatching
            }

            if (setsLearnedRecentlyIn(chatId) >= MAX_NEW_SETS_PER_CHAT_PER_DAY) {
                log.warn {
                    "chat=$chatId reached its new sticker set budget " +
                            "($MAX_NEW_SETS_PER_CHAT_PER_DAY/day); set=[$setName] not learned"
                }

                return@runCatching
            }

            val stickers = fetchSet(setName)
            syncSet(setName, stickers)
            markLearnedIn(chatId, setName)

            log.info { "learned sticker set=[$setName] (${stickers.size} stickers) from chat=$chatId" }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to learn sticker set=[$setName] from chat=$chatId" }
        }
    }

    /**
     * The sticker index for this chat's turn, or `null` when nothing is described yet —
     * which is the normal state of a fresh deployment and of a chat where nobody uses stickers.
     */
    /**
     * The shortlist a turn in [chat] is shown, or `null` when there is nothing worth showing.
     *
     * Stickers are Telegram's own model — a set is learned and resent by `file_id` — so the catalog is
     * keyed by the Telegram chat id and takes the qualified reference only to convert it here, at the
     * one place a shared caller reaches in.
     */
    suspend fun indexBlockFor(chat: ChatRef): String? = indexBlockFor(chat.telegramChatId)

    private suspend fun indexBlockFor(chatId: Long): String? {
        val entries = describedEntriesFor(chatId)
        if (entries.isEmpty()) return null

        return xmlBlock(
            "sticker_catalog",
            entries.joinToString("\n", transform = StickerEntry::catalogLine)
        )
    }

    /** Find described stickers from every set this chat has used, ranked by textual meaning. */
    internal suspend fun search(chat: ChatRef, query: String, limit: Int): List<StickerEntry> =
        searchIn(chat.telegramChatId, query, limit)

    private suspend fun searchIn(chatId: Long, query: String, limit: Int): List<StickerEntry> {
        require(limit >= 0) { "limit must not be negative" }

        val normalizedQuery = query.trim().lowercase(Locale.ROOT).replace(SEARCH_WHITESPACE_REGEX, " ")
        if (normalizedQuery.isEmpty() || limit == 0) return emptyList()

        val queryWords = SEARCH_WORD_REGEX.findAll(normalizedQuery).map { it.value }.toSet()

        return describedCatalogFor(chatId)
            .stickers
            .mapNotNull { known ->
                val entry = known.entry
                val haystack = "${entry.emoji.orEmpty()} ${entry.description}".lowercase(Locale.ROOT)
                val words = SEARCH_WORD_REGEX.findAll(haystack).map { it.value }.toSet()
                val phraseMatch = normalizedQuery in haystack
                val matchedWords = queryWords.count { queryWord -> words.any { it.matchesSearchWord(queryWord) } }

                if (!phraseMatch && matchedWords == 0) return@mapNotNull null

                val score =
                    (if (phraseMatch) 100 else 0) +
                            (if (queryWords.isNotEmpty() && matchedWords == queryWords.size) 25 else 0) +
                            matchedWords * 10

                SearchMatch(entry, score)
            }
            .sortedWith(compareByDescending<SearchMatch> { it.score }.thenBy { it.entry.id })
            .take(limit)
            .map { it.entry }
    }

    suspend fun fileIdFor(chat: ChatRef, id: Long): String? = fileIdFor(chat.telegramChatId, id)

    private suspend fun fileIdFor(chatId: Long, id: Long): String? = dbTransaction {
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

    /**
     * Telegram rejected this sticker's `file_id`. Nothing is deleted on that alone — a send fails for
     * plenty of reasons that say nothing about the sticker — so the set is only marked for an early
     * re-read, and [refreshStaleSets] decides from Telegram's own answer what to drop.
     */
    suspend fun recheckSetOf(stickerId: Long) = dbTransaction {
        val setName =
            TelegramStickersTable
                .select(TelegramStickersTable.setName)
                .where { TelegramStickersTable.id eq stickerId }
                .firstOrNull()
                ?.get(TelegramStickersTable.setName)
                ?: return@dbTransaction

        TelegramStickerSetsTable.update({ TelegramStickerSetsTable.name eq setName }) {
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

                // the day's tokens are gone, so no sticker in this backlog can be described. ending the pass
                // keeps their attempts intact — counted, they would be given up on before the budget returns.
                is DescribeOutcome.Postponed -> {
                    log.info { "sticker description postponed: the daily token budget is spent" }
                    return
                }
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
                .getOrElse { error ->
                    error.rethrowIfCancellation()
                    if (error.tokenBudgetStop() != null) return DescribeOutcome.Postponed

                    log.warn { "vision call failed for sticker id=${row.id}: ${error.message}" }
                    return DescribeOutcome.Failed
                }

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

        val fresh = live.take(MAX_STICKERS_PER_SET).filter { it.fileUniqueId !in stored }

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

    private suspend fun forgetSet(setName: String) = dbTransaction {
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
        Unit
    }

    private suspend fun markRefreshed(setName: String) = dbTransaction { markSetRefreshed(setName) }

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

    private suspend fun staleSetNames(): List<String> = dbTransaction {
        TelegramStickerSetsTable
            .select(TelegramStickerSetsTable.name)
            .where { TelegramStickerSetsTable.refreshedAt less Instant.now().minusSeconds(SET_REFRESH_INTERVAL.inWholeSeconds) }
            .orderBy(TelegramStickerSetsTable.refreshedAt to SortOrder.ASC)
            .limit(SETS_PER_REFRESH_PASS)
            .map { it[TelegramStickerSetsTable.name] }
    }

    private suspend fun isSetStored(setName: String): Boolean = dbTransaction {
        TelegramStickerSetsTable
            .select(TelegramStickerSetsTable.name)
            .where { TelegramStickerSetsTable.name eq setName }
            .limit(1)
            .any()
    }

    private data class StoredHandles(val fileId: String, val thumbnailFileId: String?)

    private data class KnownSticker(val fileUniqueId: String, val entry: StickerEntry)

    private data class StickerUsage(val fileUniqueId: String, val seenCount: Int, val lastSeenAt: Instant)

    private data class DescribedCatalog(val setNames: List<String>, val stickers: List<KnownSticker>)

    private suspend fun recordStickerUsage(chatId: Long, fileUniqueId: String) = dbTransaction {
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

        Unit
    }

    /** Records this sighting and answers how many times the chat has now used the set. */
    private suspend fun recordChatUsage(chatId: Long, setName: String): Int = dbTransaction {
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

    private suspend fun setsLearnedRecentlyIn(chatId: Long): Int = dbTransaction {
        TelegramChatStickerSetsTable
            .selectAll()
            .where {
                (TelegramChatStickerSetsTable.chatId eq chatId) and
                        (TelegramChatStickerSetsTable.learnedAt greater Instant.now().minusSeconds(NEW_SET_BUDGET_WINDOW.inWholeSeconds))
            }
            .count()
            .toInt()
    }

    private suspend fun markLearnedIn(chatId: Long, setName: String) = dbTransaction {
        TelegramChatStickerSetsTable.update({
            (TelegramChatStickerSetsTable.chatId eq chatId) and (TelegramChatStickerSetsTable.setName eq setName)
        }) {
            it[learnedAt] = Instant.now()
        }

        Unit
    }

    private suspend fun describedEntriesFor(chatId: Long): List<StickerEntry> {
        val catalog = describedCatalogFor(chatId)
        if (catalog.stickers.isEmpty()) return emptyList()

        val usage = stickerUsageFor(chatId)
        val knownByUniqueId = catalog.stickers.associateBy { it.fileUniqueId }
        val selected = linkedMapOf<Long, KnownSticker>()

        usage
            .sortedWith(compareByDescending<StickerUsage> { it.lastSeenAt }.thenByDescending { it.seenCount })
            .mapNotNull { knownByUniqueId[it.fileUniqueId] }
            .take(RECENT_INDEX_ENTRIES)
            .forEach { selected[it.entry.id] = it }

        usage
            .sortedWith(compareByDescending<StickerUsage> { it.seenCount }.thenByDescending { it.lastSeenAt })
            .mapNotNull { knownByUniqueId[it.fileUniqueId] }
            .forEach { known ->
                if (selected.size < MAX_INDEX_ENTRIES) selected[known.entry.id] = known
            }

        if (selected.size < MAX_INDEX_ENTRIES) {
            val bySet = catalog.stickers.groupBy { it.entry.setName }
            val remainingBySet =
                catalog.setNames.map { setName ->
                    bySet[setName].orEmpty().filterNot { it.entry.id in selected }
                }

            roundRobin(remainingBySet, MAX_INDEX_ENTRIES - selected.size)
                .forEach { selected[it.entry.id] = it }
        }

        return selected.values.map { it.entry }
    }

    private suspend fun describedCatalogFor(chatId: Long): DescribedCatalog = dbTransaction {
        val setNames = chatSetNames(chatId)
        if (setNames.isEmpty()) return@dbTransaction DescribedCatalog(emptyList(), emptyList())

        val stickers =
            TelegramStickersTable
                .selectAll()
                .where { (TelegramStickersTable.setName inList setNames) and TelegramStickersTable.description.isNotNull() }
                .orderBy(TelegramStickersTable.id to SortOrder.ASC)
                .mapNotNull { row -> row.toKnownStickerOrNull() }

        DescribedCatalog(setNames, stickers)
    }

    private fun chatSetNames(chatId: Long): List<String> =
        TelegramChatStickerSetsTable
            .select(TelegramChatStickerSetsTable.setName)
            .where { TelegramChatStickerSetsTable.chatId eq chatId }
            .orderBy(TelegramChatStickerSetsTable.lastSeenAt to SortOrder.DESC)
            .map { it[TelegramChatStickerSetsTable.setName] }

    private suspend fun stickerUsageFor(chatId: Long): List<StickerUsage> = dbTransaction {
        TelegramChatStickersTable
            .selectAll()
            .where { TelegramChatStickersTable.chatId eq chatId }
            .map { row ->
                StickerUsage(
                    fileUniqueId = row[TelegramChatStickersTable.fileUniqueId],
                    seenCount = row[TelegramChatStickersTable.seenCount],
                    lastSeenAt = row[TelegramChatStickersTable.lastSeenAt]
                )
            }
    }

    private fun ResultRow.toKnownStickerOrNull(): KnownSticker? =
        this[TelegramStickersTable.description]?.let { description ->
            KnownSticker(
                fileUniqueId = this[TelegramStickersTable.fileUniqueId],
                entry =
                    StickerEntry(
                        id = this[TelegramStickersTable.id].value,
                        setName = this[TelegramStickersTable.setName],
                        emoji = this[TelegramStickersTable.emoji],
                        description = description
                    )
            )
        }

    private suspend fun pendingDescriptions(): List<PendingSticker> = dbTransaction {
        TelegramStickersTable
            .selectAll()
            .where {
                TelegramStickersTable.description.isNull() and
                        (TelegramStickersTable.describeAttempts less MAX_DESCRIBE_ATTEMPTS)
            }
            .orderBy(TelegramStickersTable.id to SortOrder.ASC)
            .limit(DESCRIPTIONS_PER_PASS)
            .map { row ->
                PendingSticker(
                    id = row[TelegramStickersTable.id].value,
                    fileId = row[TelegramStickersTable.fileId],
                    thumbnailFileId = row[TelegramStickersTable.thumbnailFileId],
                    describeAttempts = row[TelegramStickersTable.describeAttempts]
                )
            }
    }

    private suspend fun storeDescription(id: Long, description: String) = dbTransaction {
        TelegramStickersTable.update({ TelegramStickersTable.id eq id }) {
            it[TelegramStickersTable.description] = description
        }
    }

    private suspend fun countFailedAttempt(id: Long, attempts: Int) {
        dbTransaction {
            TelegramStickersTable.update({ TelegramStickersTable.id eq id }) {
                it[describeAttempts] = attempts + 1
            }
        }

        if (attempts + 1 >= MAX_DESCRIBE_ATTEMPTS) {
            log.warn { "giving up on describing sticker id=$id after $MAX_DESCRIBE_ATTEMPTS attempts" }
        }
    }

    private suspend fun giveUpOnDescribing(id: Long) = dbTransaction {
        TelegramStickersTable.update({ TelegramStickersTable.id eq id }) {
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
        data object Postponed : DescribeOutcome
    }
}
