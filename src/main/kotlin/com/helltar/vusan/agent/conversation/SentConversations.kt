package com.helltar.vusan.agent.conversation

import com.helltar.vusan.agent.estimateTokens
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.ConversationScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

/**
 * The last request of each conversation exactly as it went, with what it returned, for a model whose
 * provider caches only a prefix a request repeats ([LlmModel.cachesOnlyRepeatedPrefix]).
 *
 * The stored history is shorter than what a turn sends: the current message loses its context blocks,
 * the reasoning goes, tool results are capped and only the newest two interactions keep their calls. So a
 * turn rebuilt from it stops repeating the last turn's request at that turn's message, and on such a
 * provider reads nothing of the history back. A turn that continues the last request instead only appends
 * to it, and reads all of it.
 *
 * This is a copy of what was sent, never a second history: the stored one stays what every other turn,
 * recap and provider reads, and a continuation is taken only while it still stands on it — the newest
 * stored interaction is the one the request ended with, the system prompt is the same, the memory its
 * messages carry has not changed since, and it is not much larger than the history it replaces. The
 * memory matters because every earlier message carries the memory of its own turn: an entry the person
 * had removed would live on in them. For the same reason a kept request holds no stored turn past the raw
 * history's retention, and a cleared group log drops every one kept for that chat ([forgetChat]), since its
 * messages carry what the chat was saying. Anything else drops it, and the turn is rebuilt from storage,
 * which costs that turn's cache once. Kept in memory only, for the conversations seen most recently: a
 * restart rebuilds every conversation once.
 */
class SentConversations(private val capacity: Int = DEFAULT_CAPACITY) {

    /**
     * What a conversation's last request held, [messages] from its system prompt on, standing on the stored
     * interaction [storedInteractionId], whose labels ran up to [nextCall], exclusive, with the memory of
     * [memoryFingerprint] in its messages and nothing older than [oldestTurnAt] from the stored history.
     */
    class Sent(
        val messages: List<Message>,
        val storedInteractionId: String,
        val nextCall: Int,
        val memoryFingerprint: Int,
        val oldestTurnAt: Instant,
    ) {

        init {
            require(messages.firstOrNull() is Message.System) { "a request opens with its system prompt" }
            require(nextCall > 0) { "calls are numbered from one" }
        }

        /** The history it carries, in the estimate the budget is counted in: everything after the system prompt. */
        val historyTokens: Int = messages.drop(1).sumOf(::estimateTokens)
    }

    private val sent =
        object : LinkedHashMap<ConversationScope, Sent>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConversationScope, Sent>): Boolean =
                size > capacity
        }

    // how many times each chat's log was erased: a turn that began before an erasure ends holding what was erased
    private val erasures = HashMap<ChatRef, Long>()

    /** What [remember] is handed back for [chat]: a turn reads it before it builds its request. */
    fun erasuresOf(chat: ChatRef): Long = synchronized(sent) { erasures[chat] ?: 0 }

    /** Keeps [request] for [scope], unless the chat's log was erased since the turn read [erasuresSeen]. */
    fun remember(scope: ConversationScope, request: Sent, erasuresSeen: Long) {
        synchronized(sent) {
            if ((erasures[scope.chat] ?: 0) == erasuresSeen) sent[scope] = request else sent.remove(scope)
        }
    }

    fun forget(scope: ConversationScope) {
        synchronized(sent) { sent.remove(scope) }
    }

    /** Drops what was kept for every conversation in [chat], and what a turn running there now would keep, after something the chat said was erased. */
    fun forgetChat(chat: ChatRef) {
        synchronized(sent) {
            erasures[chat] = (erasures[chat] ?: 0) + 1
            sent.keys.removeIf { it.chat == chat }
        }
    }

    /**
     * The request this turn continues, or `null` when it is rebuilt from storage, in which case whatever
     * was kept for [scope] is dropped. [memoryFingerprint] is the memory this turn carries, [storedHistoryTokens]
     * what the rebuilt history would cost, [historyBudget] what the history may cost at all, and
     * [rawRetentionCutoff] the instant before which stored turns are no longer kept.
     */
    fun continuation(
        scope: ConversationScope,
        systemPrompt: String,
        newestStoredInteractionId: String?,
        memoryFingerprint: Int,
        storedHistoryTokens: Int,
        historyBudget: Int,
        rawRetentionCutoff: Instant,
    ): Sent? {
        val request = synchronized(sent) { sent[scope] } ?: return null

        val rebuildBecause =
            when {
                request.storedInteractionId != newestStoredInteractionId -> "the stored history changed"
                (request.messages.first() as Message.System).text != systemPrompt -> "the system prompt changed"
                request.memoryFingerprint != memoryFingerprint -> "the memory changed"
                request.oldestTurnAt.isBefore(rawRetentionCutoff) -> "it holds turns past retention"
                request.historyTokens > historyBudget -> "it outgrew the history budget"
                request.historyTokens > storedHistoryTokens + GROWTH_ALLOWANCE_TOKENS -> "it outgrew the stored history"
                else -> return request
            }

        log.info {
            "last request not continued for $scope: $rebuildBecause, " +
                    "carried=${request.historyTokens} stored=$storedHistoryTokens budget=$historyBudget"
        }

        forget(scope)

        return null
    }

    private companion object {
        val log = KotlinLogging.logger {}

        const val DEFAULT_CAPACITY = 128
        const val LOAD_FACTOR = 0.75f

        // how much more than the stored history a continued one may carry before a turn is rebuilt from
        // storage: what it costs is one turn that reads only the system block back, and what it saves is
        // carrying every context block, nudge and whole tool result of the turns since. a choice, not a
        // measurement; the line above says how often it is made
        const val GROWTH_ALLOWANCE_TOKENS = 16_000
    }
}
