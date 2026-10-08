package com.helltar.vusan.agent.conversation

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ConversationScope
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * The history a prompt carries: what [planConversation] fits under the budget, recapped first when it
 * does not fit.
 *
 * At most one recap per turn: it is an extra LLM round trip in front of the user's reply. Whatever is
 * still over budget stays out of this prompt and gets its own recap on a later turn, and a recap that
 * fails, or loses the race for its checkpoint, leaves the raw history as it was.
 */
class ConversationPlanner(
    private val repository: ConversationRepository,
    private val compactor: ConversationCompactor,
) {

    suspend fun planForPrompt(scope: ConversationScope, tokenBudget: Int): ConversationPlan {
        val snapshot = repository.load(scope)
        val plan = planFor(snapshot, tokenBudget)

        if (plan.compactablePrefix.isEmpty()) return plan

        val compacted =
            try {
                compactor.compact(snapshot.summary, plan.compactablePrefix)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn {
                    "history recap failed for $scope: " +
                            e.message?.collapseWhitespaceAndCap(RECAP_ERROR_LOG_MAX_CHARS).orEmpty()
                }
                return plan
            } ?: return plan

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
            return plan
        }

        log.info {
            "history recap stored: $scope interactions=${compacted.interactionCount} " +
                    "throughMessage=${compacted.throughMessageId} chars=${compacted.summary.length}"
        }

        return planFor(repository.load(scope), tokenBudget)
    }

    private fun planFor(snapshot: ConversationSnapshot, tokenBudget: Int): ConversationPlan =
        planConversation(
            snapshot = snapshot,
            tokenBudget = tokenBudget,
            maxRecentInteractions = MAX_RECENT_INTERACTIONS,
        )

    private companion object {
        val log = KotlinLogging.logger {}

        // the count a recap is triggered by, not what the prompt ends up carrying — a window too small
        // for these still fits only what its token budget allows. kept well above that budget on a
        // large window, where a low count buys nothing and only pays for recaps.
        const val MAX_RECENT_INTERACTIONS = 40

        const val RECAP_ERROR_LOG_MAX_CHARS = 300
    }
}
