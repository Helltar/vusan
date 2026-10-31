package com.helltar.vusan.telegram.tools.sticker

import com.helltar.vusan.agent.presence.StickerShortlist
import com.helltar.vusan.agent.neutralizePromptBlocks
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
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
import org.telegram.telegrambots.meta.api.methods.stickers.GetStickerSet
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Instant
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val REGULAR_STICKER = "regular"

// one sticker unlocks a whole set of up to 120, and each description is a vision call, so only the front of a
// set is taken.
private const val MAX_STICKERS_PER_SET = 60

// the prompt keeps a small ready-to-send selection; search reaches the full catalog. a few slots go to what
// the chat used lately, the rest is drawn afresh per turn, weighted toward the chat's frequent sets, so turns
// see different stickers and the bot's taste is not just the group's favorites. what the bot sent lately is
// left out so it does not repeat itself.
private const val MAX_INDEX_ENTRIES = 24
private const val RECENT_INDEX_ENTRIES = 6
private const val REMEMBERED_SENDS_PER_CHAT = 20
private const val MAX_DESCRIPTION_CHARS = 90

// learning a set is the only expensive step (up to MAX_STICKERS_PER_SET vision calls, once). these tie that
// bill to real use: a set nobody uses twice is never learned, and a chat can pull in only a few new sets a
// day. a set already known from another chat is free. the deployment-wide cap bounds the worst day at
// MAX_NEW_SETS_PER_DAY × MAX_STICKERS_PER_SET calls whatever the chat count.
private const val MIN_USES_BEFORE_LEARNING = 2
private const val MAX_NEW_SETS_PER_CHAT_PER_DAY = 3
private const val MAX_NEW_SETS_PER_DAY = 6
private val NEW_SET_BUDGET_WINDOW = 24.hours

private const val DESCRIPTIONS_PER_PASS = 20
private const val SETS_PER_REFRESH_PASS = 3
private val DESCRIPTION_PAUSE = 300.milliseconds
private val BACKLOG_POLL_INTERVAL = 60.seconds

// sets change rarely and a re-read costs an API call; daily is enough for a deleted set to stop being
// offered.
private val SET_REFRESH_INTERVAL = 24.hours

private const val SKIP_SENTINEL = "SKIP"

private val SEARCH_WORD_REGEX = Regex("[\\p{L}\\p{N}]+")
private val SEARCH_WHITESPACE_REGEX = Regex("\\s+")

private const val STICKER_VISION_FOCUS =
    "This image is a Telegram sticker, used in chat the way a reaction or a punchline is. " +
            "In at most 12 words, name who or what is shown, what they are doing or feeling, and the mood. " +
            "Answer in English with that phrase alone — no preamble, no full sentence, no mention of it being a sticker. " +
            "If you will not describe it for any reason, answer with the single word `$SKIP_SENTINEL` and nothing else."

// a vision model asked about an explicit sticker answers in prose instead of erroring, and that prose must
// never become an entry.
private val REFUSAL_REGEX =
    Regex(
        "^(i'?m sorry|i am sorry|sorry\\b|i can'?t|i cannot|i'?m unable|i am unable|unable to|" +
                "i won'?t|i will not|cannot assist|can'?t assist|cannot help|can'?t help)",
        RegexOption.IGNORE_CASE,
    )

/**
 * Draws up to [limit] items without replacement: each pick chooses a source with probability proportional to
 * its weight plus one (so an unweighted source still gets picked), then a random item of it. An exhausted
 * source leaves the draw; [sources] are consumed.
 */
internal fun <T> weightedDraw(
    sources: Map<String, MutableList<T>>,
    weights: Map<String, Int>,
    limit: Int,
    random: Random,
): List<T> {
    require(limit >= 0) { "limit must not be negative" }

    val open = sources.filterValues { it.isNotEmpty() }.toMutableMap()

    return buildList {
        while (size < limit && open.isNotEmpty()) {
            val chosen = pickWeighted(open.keys, weights, random)
            val items = open.getValue(chosen)

            add(items.removeAt(random.nextInt(items.size)))
            if (items.isEmpty()) open.remove(chosen)
        }
    }
}

private fun pickWeighted(names: Set<String>, weights: Map<String, Int>, random: Random): String {
    var roll = random.nextInt(names.sumOf { it.weight(weights) })

    for (name in names) {
        roll -= name.weight(weights)
        if (roll < 0) return name
    }

    return names.last()
}

private fun String.weight(weights: Map<String, Int>): Int = (weights[this] ?: 0) + 1

internal data class StickerEntry(val id: Long, val setName: String, val emoji: String?, val description: String)

// the description comes from an image someone sent, and the line lands in `<sticker_catalog>` and in tool
// results, so it is defused once here.
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
 * The stickers the bot can send, learned from the ones people use.
 *
 * A sticker seen in an allowlisted chat reveals its set; the set is pulled in whole and each sticker
 * described once by vision, so the model picks by meaning rather than emoji. The catalog is global, the index
 * per chat.
 */
class StickerCatalog(
    private val client: TelegramClient,
    private val vision: ImageVisionClient,
    private val random: Random = Random.Default,
) : StickerShortlist {

    private val repository = StickerRepository()

    // what the bot sent, per chat, newest last. in memory on purpose: a restart costs at most one repeat
    private val sentLately = HashMap<Long, ArrayDeque<Long>>()

    /** Records a sticker seen in a chat, learning its set once it has earned it. */
    suspend fun observe(chatId: Long, sticker: Sticker) {
        val setName = sticker.setName?.takeIf { it.isNotBlank() } ?: return
        if (sticker.type != null && sticker.type != REGULAR_STICKER) return

        runCatching {
            repository.recordStickerUsage(chatId, sticker.fileUniqueId)
            val seenCount = repository.recordChatUsage(chatId, setName)

            // a set known from anywhere costs nothing to offer, so the cost guards below skip it.
            if (repository.isSetStored(setName)) return@runCatching

            if (seenCount < MIN_USES_BEFORE_LEARNING) {
                log.debug { "sticker set=[$setName] seen $seenCount time(s) in chat=$chatId; not learning it yet" }
                return@runCatching
            }

            val budgetStart = Instant.now().minusSeconds(NEW_SET_BUDGET_WINDOW.inWholeSeconds)

            if (repository.setsLearnedSince(budgetStart, chatId) >= MAX_NEW_SETS_PER_CHAT_PER_DAY) {
                log.warn {
                    "chat=$chatId reached its new sticker set budget " +
                            "($MAX_NEW_SETS_PER_CHAT_PER_DAY/day); set=[$setName] not learned"
                }

                return@runCatching
            }

            if (repository.setsLearnedSince(budgetStart) >= MAX_NEW_SETS_PER_DAY) {
                log.warn {
                    "the bot reached its new sticker set budget ($MAX_NEW_SETS_PER_DAY/day); " +
                            "set=[$setName] from chat=$chatId not learned"
                }

                return@runCatching
            }

            val stickers = fetchSet(setName)
            repository.syncSet(setName, stickers, MAX_STICKERS_PER_SET)
            repository.markLearnedIn(chatId, setName)

            log.info { "learned sticker set=[$setName] (${stickers.size} stickers) from chat=$chatId" }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to learn sticker set=[$setName] from chat=$chatId" }
        }
    }

    /**
     * The shortlist for a turn in [chat], or `null` when there is nothing worth showing. Stickers are
     * telegram's own (learned and resent by `file_id`), so the catalog is keyed by the telegram chat id and
     * converts the qualified reference here.
     */
    override suspend fun indexBlockFor(chat: ChatRef): String? = indexBlockFor(chat.telegramChatId)

    private suspend fun indexBlockFor(chatId: Long): String? {
        val entries = describedEntriesFor(chatId)
        if (entries.isEmpty()) return null

        return xmlBlock(
            "sticker_catalog",
            entries.joinToString("\n", transform = StickerEntry::catalogLine),
        )
    }

    /** Finds described stickers from every set this chat used, ranked by textual meaning. */
    internal suspend fun search(chat: ChatRef, query: String, limit: Int): List<StickerEntry> =
        searchIn(chat.telegramChatId, query, limit)

    private suspend fun searchIn(chatId: Long, query: String, limit: Int): List<StickerEntry> {
        require(limit >= 0) { "limit must not be negative" }

        val normalizedQuery = query.trim().lowercase(Locale.ROOT).replace(SEARCH_WHITESPACE_REGEX, " ")
        if (normalizedQuery.isEmpty() || limit == 0) return emptyList()

        val queryWords = SEARCH_WORD_REGEX.findAll(normalizedQuery).map { it.value }.toSet()

        return repository.describedStickersFor(chatId)
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

    override suspend fun fileIdFor(chat: ChatRef, id: Long): String? = fileIdFor(chat.telegramChatId, id)

    /** Notes a sticker the bot sent into [chatId], so the shortlist skips it for a while. */
    fun recordSent(chatId: Long, id: Long) {
        synchronized(sentLately) {
            val sent = sentLately.getOrPut(chatId) { ArrayDeque() }

            sent.remove(id)
            sent.addLast(id)
            while (sent.size > REMEMBERED_SENDS_PER_CHAT) sent.removeFirst()
        }
    }

    private fun sentLatelyIn(chatId: Long): Set<Long> =
        synchronized(sentLately) { sentLately[chatId]?.toSet().orEmpty() }

    private suspend fun fileIdFor(chatId: Long, id: Long): String? = repository.fileIdFor(chatId, id)

    /**
     * Telegram rejected this sticker's `file_id`. A send fails for many unrelated reasons, so nothing is
     * deleted: the set is marked for an early re-read, and [refreshStaleSets] decides from telegram's answer
     * what to drop.
     */
    suspend fun recheckSetOf(stickerId: Long) {
        val setName = repository.queueRecheck(stickerId) ?: return

        log.info { "sticker id=$stickerId was rejected; set=[$setName] queued for an early re-read" }
    }

    /**
     * Describes newly learned stickers in the background. Vision failures are expected: an undescribed
     * sticker stays out of the index and is retried later.
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
     * Re-reads the sets learned longest ago. A set's owner can add, remove or delete stickers, so the catalog
     * has to be checked against telegram now and then, or the bot keeps proposing stickers that fail to send.
     */
    private suspend fun refreshStaleSets() {
        val before = Instant.now().minusSeconds(SET_REFRESH_INTERVAL.inWholeSeconds)

        for (setName in repository.staleSetNames(before, SETS_PER_REFRESH_PASS)) {
            val stickers =
                runCatching { fetchSet(setName) }
                    .onFailure { error ->
                        error.rethrowIfCancellation()

                        if (error.isStickerSetGone()) {
                            repository.forgetSet(setName)
                            log.info { "sticker set=[$setName] no longer exists; dropped from the catalog" }
                        } else {
                            // a transient failure is no reason to drop described stickers; back off instead
                            // of retrying every poll.
                            log.warn { "could not refresh sticker set=[$setName]: ${error.message}" }
                            repository.markRefreshed(setName)
                        }
                    }
                    .getOrNull()
                    ?: continue

            repository.syncSet(setName, stickers, MAX_STICKERS_PER_SET)
        }
    }

    private suspend fun describePending() {
        val pending = repository.pendingDescriptions(DESCRIPTIONS_PER_PASS)
        if (pending.isEmpty()) return

        log.info { "describing ${pending.size} sticker(s)" }

        for (row in pending) {
            when (val outcome = describe(row)) {
                is DescribeOutcome.Described -> repository.storeDescription(row.id, outcome.text)

                // a refusal is a verdict: asking again only spends another call.
                is DescribeOutcome.Refused -> {
                    log.info { "sticker id=${row.id} left out of the catalog: vision would not describe it" }
                    repository.giveUpOnDescribing(row.id)
                }

                is DescribeOutcome.Failed ->
                    if (repository.countDescribeAttempt(row.id, row.describeAttempts) >= StickerRepository.MAX_DESCRIBE_ATTEMPTS)
                        log.warn { "giving up on describing sticker id=${row.id} after ${StickerRepository.MAX_DESCRIBE_ATTEMPTS} attempts" }
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
                loadBytes = { bytes },
            )

        val answer =
            runCatching { vision.describe(image, bytes, STICKER_VISION_FOCUS) }
                .getOrElse { error ->
                    error.rethrowIfCancellation()
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

    private suspend fun describedEntriesFor(chatId: Long): List<StickerEntry> {
        val stickers = repository.describedStickersFor(chatId)
        if (stickers.isEmpty()) return emptyList()

        val left = sentLatelyIn(chatId)
        val knownByUniqueId = stickers.associateBy { it.fileUniqueId }
        val selected = linkedMapOf<Long, KnownSticker>()

        // what the chat reached for lately
        repository.stickerUsageFor(chatId)
            .sortedWith(compareByDescending<StickerUsage> { it.lastSeenAt }.thenByDescending { it.seenCount })
            .mapNotNull { knownByUniqueId[it.fileUniqueId] }
            .filterNot { it.entry.id in left }
            .take(RECENT_INDEX_ENTRIES)
            .forEach { selected[it.entry.id] = it }

        // the rest is a fresh draw weighted toward the chat's frequent sets, while every set keeps a chance
        val remainingBySet =
            stickers
                .filterNot { it.entry.id in selected || it.entry.id in left }
                .groupBy { it.entry.setName }
                .mapValues { (_, stickers) -> stickers.toMutableList() }

        weightedDraw(remainingBySet, repository.setWeightsFor(chatId), MAX_INDEX_ENTRIES - selected.size, random)
            .forEach { selected[it.entry.id] = it }

        return selected.values.map { it.entry }
    }

    private sealed interface DescribeOutcome {
        data class Described(val text: String) : DescribeOutcome
        data object Refused : DescribeOutcome
        data object Failed : DescribeOutcome
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
