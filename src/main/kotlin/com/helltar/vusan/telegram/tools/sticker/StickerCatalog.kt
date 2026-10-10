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

// one member throwing a sticker unlocks its whole set, which can hold 120 of them. describing every
// one costs a vision call, so only the front of a set is taken — enough to speak the set's language
// without paying for its long tail.
private const val MAX_STICKERS_PER_SET = 60

// the prompt keeps a small ready-to-send selection; the search tool reaches the full chat catalog
// when none of these fits. a few slots go to what the chat itself reached for lately, the rest is
// drawn afresh for every turn from everything the chat's sets hold, a set the chat uses often weighing
// more — so two turns see different stickers and the bot's taste is not the group's favorites. what
// the bot itself sent lately is left out, so it cannot keep reaching for the same one.
private const val MAX_INDEX_ENTRIES = 24
private const val RECENT_INDEX_ENTRIES = 6
private const val REMEMBERED_SENDS_PER_CHAT = 20
private const val MAX_DESCRIPTION_CHARS = 90

// pulling in a set is the only expensive thing here — up to MAX_STICKERS_PER_SET vision calls, paid
// once. these keep that bill tied to what a chat actually uses: a set nobody reaches for twice is
// never learned, and no chat can pull in more than a handful of new sets a day however many stickers
// it throws. neither applies to a set already known from another chat, which costs nothing to offer.
// the per-chat cap alone still grows with the number of chats the bot sits in, so a cap on the whole
// deployment bounds the worst day at MAX_NEW_SETS_PER_DAY × MAX_STICKERS_PER_SET vision calls,
// whatever the chat count.
private const val MIN_USES_BEFORE_LEARNING = 2
private const val MAX_NEW_SETS_PER_CHAT_PER_DAY = 3
private const val MAX_NEW_SETS_PER_DAY = 6
private val NEW_SET_BUDGET_WINDOW = 24.hours

private const val DESCRIPTIONS_PER_PASS = 20
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
        RegexOption.IGNORE_CASE,
    )

/**
 * Draw up to [limit] items without replacement. Each pick chooses a source with a probability
 * proportional to its weight plus one — a source nobody weighted still gets its turn — and then a
 * random item of that source; a source that runs out leaves the draw. [sources] are consumed.
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
    private val vision: ImageVisionClient,
    private val random: Random = Random.Default,
) : StickerShortlist {

    private val repository = StickerRepository()

    // what the bot itself sent, per chat, newest last. process memory on purpose: a restart losing it
    // costs at most one repeat
    private val sentLately = HashMap<Long, ArrayDeque<Long>>()

    /** Record a sticker seen in a chat, pulling in its set once that set has earned it. */
    suspend fun observe(chatId: Long, sticker: Sticker) {
        val setName = sticker.setName?.takeIf { it.isNotBlank() } ?: return
        if (sticker.type != null && sticker.type != REGULAR_STICKER) return

        runCatching {
            repository.recordStickerUsage(chatId, sticker.fileUniqueId)
            val seenCount = repository.recordChatUsage(chatId, setName)

            // a set already known from anywhere is free to offer here: no fetch, no vision, so neither
            // of the cost guards below applies to it.
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
     * The shortlist a turn in [chat] is shown, or `null` when there is nothing worth showing.
     *
     * Stickers are Telegram's own model — a set is learned and resent by `file_id` — so the catalog is
     * keyed by the Telegram chat id and takes the qualified reference only to convert it here, at the
     * one place a shared caller reaches in.
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

    /** Find described stickers from every set this chat has used, ranked by textual meaning. */
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

    /** Note a sticker the bot sent into [chatId], so the shortlist stops offering it for a while. */
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
     * Telegram rejected this sticker's `file_id`. Nothing is deleted on that alone — a send fails for
     * plenty of reasons that say nothing about the sticker — so the set is only marked for an early
     * re-read, and [refreshStaleSets] decides from Telegram's own answer what to drop.
     */
    suspend fun recheckSetOf(stickerId: Long) {
        val setName = repository.queueRecheck(stickerId) ?: return

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
                            // a transient failure is no reason to throw away described stickers; back off
                            // instead of retrying it on every poll.
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

                // a refusal is a verdict, not a hiccup: asking again would only spend another call.
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

        // what the chat itself reached for lately: the stickers people are using right now
        repository.stickerUsageFor(chatId)
            .sortedWith(compareByDescending<StickerUsage> { it.lastSeenAt }.thenByDescending { it.seenCount })
            .mapNotNull { knownByUniqueId[it.fileUniqueId] }
            .filterNot { it.entry.id in left }
            .take(RECENT_INDEX_ENTRIES)
            .forEach { selected[it.entry.id] = it }

        // the rest is a fresh draw from everything the chat's sets hold, a set the chat uses often
        // weighing more, so a popular set shows up more while every set keeps a chance
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
