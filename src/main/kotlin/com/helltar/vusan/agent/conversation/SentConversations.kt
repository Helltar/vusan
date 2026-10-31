package com.helltar.vusan.agent.conversation

import com.helltar.vusan.agent.estimateTokens
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.ConversationScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

/**
 * The last request of each conversation exactly as sent, plus its reply, for a provider that caches only a
 * prefix a request repeats ([LlmModel.cachesOnlyRepeatedPrefix]).
 *
 * Stored history is shorter than what a turn sends: the current message loses its context blocks, reasoning
 * goes, tool results are capped and only the newest two interactions keep their calls. A turn rebuilt from it
 * diverges at the last turn's message and, on such a provider, reads none of the history from cache. A turn
 * continuing the last request only appends to it and reads all of it.
 *
 * This is a copy of what was sent, never a second history. A continuation is taken only while it still
 * matches storage: its last interaction is the newest stored one, the system prompt is the same, the memory
 * in its messages is unchanged (each message carries its own turn's memory, so a removed entry would live
 * on), and it is not much larger than the history it replaces. For the same reason it holds no turn past
 * raw-history retention, and a cleared group log drops everything kept for that chat ([forgetChat]). Anything
 * else drops it and the turn is rebuilt, costing that turn's cache once. In memory only, for the most recent
 * conversations.
 */
class SentConversations(private val capacity: Int = DEFAULT_CAPACITY) {

    /**
     * A conversation's last request: [messages] from the system prompt on, built on stored interaction
     * [storedInteractionId], with labels up to [nextCall] (exclusive), the memory of [memoryFingerprint], and
     * nothing from stored history older than [oldestTurnAt].
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

        /** The history it carries in the budget's estimate: everything after the system prompt. */
        val historyTokens: Int = messages.drop(1).sumOf(::estimateTokens)
    }

    private val sent =
        object : LinkedHashMap<ConversationScope, Sent>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConversationScope, Sent>): Boolean =
                size > capacity
        }

    // erasures per chat's log: a turn that began before an erasure ends holding what was erased
    private val erasures = HashMap<ChatRef, Long>()

    /** What [remember] is given back for [chat]; a turn reads it before building its request. */
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

    /**
     * Drops what was kept for every conversation in [chat], and what a turn running there would keep, after
     * the chat's log was erased.
     */
    fun forgetChat(chat: ChatRef) {
        synchronized(sent) {
            erasures[chat] = (erasures[chat] ?: 0) + 1
            sent.keys.removeIf { it.chat == chat }
        }
    }

    /**
     * The request this turn continues, or `null` when it is rebuilt from storage, dropping what was kept for
     * [scope]. [memoryFingerprint] is this turn's memory, [storedHistoryTokens] the rebuilt history's cost,
     * [historyBudget] the history's limit, and [rawRetentionCutoff] the instant before which stored turns are
     * gone.
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

        // how much more than the stored history a continuation may carry before rebuilding: a rebuild costs
        // one turn reading only the system block from cache, a continuation carries every context block,
        // nudge and full tool result since. a choice, not a measurement; the line above logs how often it is
        // made
        const val GROWTH_ALLOWANCE_TOKENS = 16_000
    }
}
