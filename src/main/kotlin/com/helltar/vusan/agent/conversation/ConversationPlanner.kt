package com.helltar.vusan.agent.conversation

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ConversationScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The history a prompt carries: what [planConversation] fits under the budget, and its recap.
 *
 * A recap is an LLM round trip, so it is made ahead of the reply only when the history does not fit at all.
 * One that only the interaction count asks for waits for the turn to end: [recapLater] hands it to the worker
 * [launchIn] starts, one conversation at a time. A recap that fails or loses the race for its checkpoint
 * leaves the raw history as is, and a later turn tries again.
 */
class ConversationPlanner(
    private val repository: ConversationRepository,
    private val compactor: ConversationCompactor,
) {

    // a conversation is queued once however many turns end before the worker reaches it
    private val queued = ConcurrentHashMap.newKeySet<ConversationScope>()
    private val due = Channel<Pair<ConversationScope, Int>>(Channel.UNLIMITED)

    suspend fun planForPrompt(scope: ConversationScope, tokenBudget: Int): ConversationPlan {
        val snapshot = repository.load(scope)
        val plan = planFor(snapshot, tokenBudget)

        if (!plan.overBudget || plan.compactablePrefix.isEmpty()) return plan

        return if (recap(scope, snapshot, plan)) planFor(repository.load(scope), tokenBudget) else plan
    }

    /** Asks for the recap this conversation is due, if any, after the turn that wrote it. */
    fun recapLater(scope: ConversationScope, tokenBudget: Int) {
        if (queued.add(scope)) due.trySend(scope to tokenBudget)
    }

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            for ((conversation, tokenBudget) in due) {
                // released before the work, so a turn ending meanwhile can queue the next one
                queued.remove(conversation)

                runCatching {
                    val snapshot = repository.load(conversation)
                    val plan = planFor(snapshot, tokenBudget)

                    if (plan.compactablePrefix.isNotEmpty()) recap(conversation, snapshot, plan)
                }.onFailure {
                    it.rethrowIfCancellation()
                    log.warn(it) { "history recap pass failed for $conversation" }
                }
            }
        }

    private suspend fun recap(scope: ConversationScope, snapshot: ConversationSnapshot, plan: ConversationPlan): Boolean {
        val compacted =
            try {
                compactor.compact(snapshot.summary, plan.compactablePrefix)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn {
                    "history recap failed for $scope: " +
                            e.message?.collapseWhitespaceAndCap(RECAP_ERROR_LOG_MAX_CHARS).orEmpty()
                }
                return false
            } ?: return false

        // a turn may have stored its own recap meanwhile, or a clear removed what this one covered: the
        // checkpoint refuses both
        val stored =
            repository.storeSummary(
                scope = scope,
                expectedThroughMessageId = snapshot.summarizedThroughMessageId,
                throughMessageId = compacted.throughMessageId,
                content = compacted.summary,
            )

        if (!stored) {
            log.warn {
                "history recap checkpoint changed before store for $scope; keeping the raw history"
            }

            return false
        }

        log.info {
            "history recap stored: $scope interactions=${compacted.interactionCount} " +
                    "throughMessage=${compacted.throughMessageId} chars=${compacted.summary.length}"
        }

        return true
    }

    private fun planFor(snapshot: ConversationSnapshot, tokenBudget: Int): ConversationPlan =
        planConversation(
            snapshot = snapshot,
            tokenBudget = tokenBudget,
            maxRecentInteractions = MAX_RECENT_INTERACTIONS,
        )

    private companion object {
        val log = KotlinLogging.logger {}

        // the count that triggers a recap, not what the prompt carries: a small window still fits only its
        // token budget. kept well above that budget on large windows, where a low count only pays for recaps.
        const val MAX_RECENT_INTERACTIONS = 40

        const val RECAP_ERROR_LOG_MAX_CHARS = 300
    }
}
