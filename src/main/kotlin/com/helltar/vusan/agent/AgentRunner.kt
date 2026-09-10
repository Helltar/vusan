package com.helltar.vusan.agent

import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.grouplog.renderGroupLog
import com.helltar.vusan.agent.grouplog.withoutExchangesWith
import com.helltar.vusan.agent.conversation.*
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.memoryOwner
import com.helltar.vusan.budget.BudgetOwner
import com.helltar.vusan.budget.TokenBudget
import com.helltar.vusan.budget.TokenBudgetStop
import com.helltar.vusan.budget.tokenBudgetStop
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.CodexAuthException
import com.helltar.vusan.config.ConversationConfig
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.RequestContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// `<recent_chat>` rides along on every group turn, so it is budgeted for cheapness, not for detail:
// enough to know what is being talked about, never enough to answer a recap question on its own.
private const val RECENT_CHAT_MAX_CHARS = 1_000
private const val RECENT_CHAT_LINE_CHARS = 120
private const val RECENT_CHAT_OVERFETCH = 3

private const val EMERGENCY_SUMMARY_MAX_CHARS = 1_500
private const val LOG_REPLY_MAX_CHARS = 300
private const val PROVIDER_ERROR_LOG_MAX_CHARS = 300

/**
 * One turn to run: where it came from, and the two texts it is made of.
 *
 * [prompt] is what the model is shown and [conversationEntry] what history keeps, which are not the
 * same string — a turn's prompt carries context the stored exchange has no reason to repeat.
 */
data class AgentRequest(
    val context: RequestContext,
    val prompt: String,
    val conversationEntry: String
)

data class AgentResult(
    val outputs: List<OutboxItem>,
    val comment: String?,
    val commentToPrivate: Boolean = false,
    // the run ended in an error and produced no answer: `comment` is the canned failure reply. `outputs`
    // may still hold what the turn put in the chat before it broke, which delivery records without
    // sending again.
    val failed: Boolean = false
)

class AgentRunner(
    private val agentFactory: AgentFactory,
    private val conversation: ConversationRepository,
    private val memory: MemoryRepository,
    private val conversationCompactor: ConversationCompactor,
    private val conversationConfig: ConversationConfig = ConversationConfig(),
    // what the chat's sticker shortlist looks like, if this deployment has one. a function rather than
    // the catalog itself: the catalog resends by `file_id`, which is one messenger's own model, and the
    // runner has no business holding something that needs a client to exist.
    private val stickerCatalog: (suspend (ChatRef) -> String?)? = null,
    private val groupLog: GroupLogRepository? = null,
    private val groupLogConfig: GroupLogConfig = GroupLogConfig(),
    private val tokenBudget: TokenBudget = TokenBudget()
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val conversationLocks = HashMap<ConversationScope, ConversationLock>()
    private val running = RunningTurns<ConversationScope>()

    suspend fun handle(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        narrator: TurnNarrator? = null
    ): AgentResult {
        val context = request.context
        val key = context.scope
        val lock = retainLock(key)

        try {
            if (!lock.tryLock()) {
                return AgentResult(outputs = emptyList(), comment = Messages.of(context.language).busyReply)
            }

            try {
                return running.track(key) { runAgent(request, onToolStarting, narrator) }
            } finally {
                lock.unlock()
            }
        } finally {
            releaseLock(key)
        }
    }

    /**
     * Cancels the turn this conversation is running, and reports whether there was one. What it happened
     * to be doing does not matter: the model call, the tool it is inside and everything that tool started
     * are children of the same job. A workspace command outlives it on its own machine, bounded by its own
     * timeout, and the model can list and cancel those separately.
     */
    fun stop(scope: ConversationScope): Boolean =
        running.cancel(scope)

    suspend fun handleScheduled(request: AgentRequest): AgentResult =
        handleQueued(request)

    suspend fun handleQueued(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        narrator: TurnNarrator? = null
    ): AgentResult {
        val context = request.context
        val key = context.scope
        val lock = retainLock(key)

        try {
            return lock.withLock { running.track(key) { runAgent(request, onToolStarting, narrator) } }
        } finally {
            releaseLock(key)
        }
    }

    // a turn persists its own history under this lock, so an outside clear (the `/clear` command) has to
    // take the same lock or a turn already in flight would append itself back into the wiped history.
    // the lock is keyed by what it guards, one conversation, so the same person writing in two chats is
    // served in both instead of being told the bot is busy.
    // `clearConversation` runs inside a turn and must keep using the repository directly.
    suspend fun clearConversation(scope: ConversationScope) {
        val lock = retainLock(scope)

        try {
            lock.withLock { conversation.clear(scope) }
        } finally {
            releaseLock(scope)
        }
    }

    private suspend fun runAgent(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit,
        narrator: TurnNarrator?
    ): AgentResult {
        val context = request.context

        tokenBudget.stopFor(context.user)?.let { stop ->
            log.warn {
                "token budget stop before the turn: chat=${context.chat.id} user=${context.sender.id} " +
                        "reason=${stop::class.simpleName} resetsIn=${stop.untilReset}"
            }

            return AgentResult(outputs = emptyList(), comment = Messages.of(context.language).replyFor(stop))
        }

        // the turn spends tokens in places that never see the request — a history recap, a vision tool —
        // so its author rides along in the coroutine context and every one of those calls is charged to them.
        return withContext(BudgetOwner(context.user)) { runTurn(request, onToolStarting, narrator) }
    }

    private suspend fun runTurn(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit,
        narrator: TurnNarrator?
    ): AgentResult {
        val context = request.context
        val userMemory = if (context.sender.isPerson) memory.load(context.user.memoryOwner) else emptyList()
        val chatMemory = if (context.chat.isPrivate) emptyList() else memory.load(context.chatRef.memoryOwner)

        val currentTurn =
            currentTurnPrompt(
                userInput = request.prompt,
                context = context,
                // the turn is stored only after the run, so this still points at the previous exchange.
                previousExchangeAt = conversation.lastInteractionAt(context.scope),
                userMemory = userMemory,
                chatMemory = chatMemory,
                recentChat = recentChatFor(context),
                stickerCatalog = stickerCatalogFor(context)
            )

        val outbox = BotOutbox(context.chat.capabilities)
        val preparation =
            agentFactory.prepare(
                context = context,
                outbox = outbox,
                currentTurn = currentTurn,
                narrator = narrator
            )

        val conversationPlan =
            conversationPlanForPrompt(context.scope, preparation.tokenBudget.conversationTokens)
        val plannedInputTokens = preparation.tokenBudget.fixedPromptTokens + conversationPlan.estimatedTokens

        log.info {
            "prompt history loaded: user=${context.sender.id} chat=${context.chat.id} " +
                    "storedInteractions=${conversationPlan.stats.storedInteractions} storedMessages=${conversationPlan.stats.storedMessages} " +
                    "storedChars=${conversationPlan.stats.storedChars} unsummarized=${conversationPlan.stats.unsummarizedInteractions} " +
                    "includedInteractions=${conversationPlan.includedInteractions} turns=${conversationPlan.prompt.turns.size} " +
                    "summaryChars=${conversationPlan.prompt.summary?.length ?: 0} exactToolInteractions=${conversationPlan.exactToolInteractions} " +
                    "userMemory=${userMemory.size} chatMemory=${chatMemory.size} " +
                    "promptChars=${request.prompt.length} historyChars=${request.conversationEntry.length} " +
                    "attachedFiles=${context.attachedFiles.size}"
        }

        log.info {
            "prompt context plan: user=${context.sender.id} chat=${context.chat.id} " +
                    "contextTokens=${preparation.tokenBudget.contextWindowTokens} " +
                    "fixedTokens=${preparation.tokenBudget.fixedPromptTokens} " +
                    "historyBudget=${preparation.tokenBudget.conversationTokens} " +
                    "conversationTokens=${conversationPlan.estimatedTokens} " +
                    "responseReserve=${preparation.tokenBudget.responseReserveTokens} " +
                    "agentReserve=${preparation.tokenBudget.agentReserveTokens} " +
                    "safetyReserve=${preparation.tokenBudget.safetyReserveTokens} " +
                    "plannedInputTokens=$plannedInputTokens " +
                    "contextPercent=${preparation.tokenBudget.contextPercentFor(conversationPlan.estimatedTokens)}"
        }

        val toolEvents = mutableListOf<ToolEvent>()
        val tokenUsages = mutableListOf<TokenUsage>()

        val answer =
            try {
                runAgentWithConversation(
                    scope = context.scope,
                    currentTurn = currentTurn,
                    conversation = conversationPlan.prompt,
                    preparation = preparation,
                    outbox = outbox,
                    toolEvents = toolEvents,
                    tokenUsages = tokenUsages,
                    onToolStarting = onToolStarting
                )
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                budgetStopReply(context, e)?.let { return AgentResult(outputs = emptyList(), comment = it) }

                // logs the cause either way; the reply itself is only used when nothing was delivered.
                val failureReply = replyForAgentFailure(context, e)

                // an announced plan is a promise, not an answer, so a turn holding nothing else owes the
                // user the reason it stopped. Without this the chat sees "building it now…" and then
                // silence forever, which is the worst reading of a failure there is.
                if (!outbox.hasQueuedOutput) {
                    return AgentResult(outputs = outbox.pending, comment = failureReply, failed = true)
                }

                // the run died with an answer already queued. deliver that instead of replacing it with the
                // canned failure, and let the turn finish normally so the work reaches the history — a
                // scheduled task then counts the attempt as delivered rather than paying for it all again.
                log.warn {
                    "agent.run failed after partial delivery for chat=${context.chat.id} user=${context.sender.id}; " +
                            "sending ${outbox.pending.size} queued output(s)"
                }

                ""
            }

        log.info {
            "token usage: chat=${context.chat.id} user=${context.sender.id} ${tokenUsageLogSummary(tokenUsages)}"
        }

        val outputs = outbox.pending
        val comment = extractFinalComment(answer, outputs)

        if (outputs.isEmpty() && comment.isNullOrBlank()) {
            log.info { "agent produced no output for chat=${context.chat.id} user=${context.sender.id}; staying silent" }
            return AgentResult(outputs = emptyList(), comment = null)
        }

        val assistantText = assistantTextForHistory(outputs, comment)

        val turns =
            buildTurns(
                userEntry = request.conversationEntry,
                toolEvents = toolEvents,
                assistantText = assistantText
            )

        log.info {
            "agent reply: chat=${context.chat.id} user=${context.sender.id} " +
                    "outputs=[${outputsLogSummary(outputs)}] " +
                    "text=[${assistantText?.collapseWhitespaceAndCap(LOG_REPLY_MAX_CHARS).orEmpty()}]"
        }

        if (turns.isNotEmpty()) {
            conversation.appendInteraction(context.scope, turns)
        }

        val pruned =
            conversation.pruneCompacted(
                scope = context.scope,
                maxStoredInteractions = conversationConfig.maxStoredInteractions,
                rawRetentionCutoff = Instant.now().minus(conversationConfig.retentionDays.toLong(), ChronoUnit.DAYS)
            )

        if (pruned > 0) {
            log.info { "history raw retention pruned: user=${context.sender.id} interactions=$pruned" }
        }

        return AgentResult(outputs, comment, outbox.redirectToPrivate)
    }

    // the catalog is worth its tokens only where the reply can actually carry a sticker: a group that
    // forbids them keeps StickerTools out of the registry, so an index here would offer the model a
    // shortlist it has no tool to send.
    private suspend fun stickerCatalogFor(context: RequestContext): String? =
        stickerCatalog
            ?.takeIf { context.chat.capabilities.stickersAndAnimations }
            ?.invoke(context.chatRef)

    // what the group was saying just before this turn. in a group the bot only ever sees the messages
    // addressed to it, so without this a question like "and what do you think?" arrives with no subject.
    // the triggering message is left out — the model is already being shown it as the request itself.
    private suspend fun recentChatFor(context: RequestContext): String? {
        val repository = groupLog?.takeIf { groupLogConfig.recentChatEnabled && !context.chat.isPrivate } ?: return null

        val entries =
            try {
                repository.recent(
                    chat = context.chatRef,
                    // over-fetch: dropping this user's own exchanges below must not thin the slice out.
                    limit = groupLogConfig.recentMessages * RECENT_CHAT_OVERFETCH,
                    since = Instant.now().minus(groupLogConfig.recentMinutes.toLong(), ChronoUnit.MINUTES),
                    excludeMessageId = context.messageId
                )
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn(e) { "failed to load the recent chat slice for chat=${context.chat.id}" }
                return null
            }

        val recent =
            entries
                .withoutExchangesWith(context.sender.id)
                .takeLast(groupLogConfig.recentMessages)

        return renderGroupLog(recent, ZoneId.systemDefault(), RECENT_CHAT_LINE_CHARS, RECENT_CHAT_MAX_CHARS)
            .text
            .takeIf { it.isNotBlank() }
    }

    // at most one recap per turn: it is an extra LLM round trip in front of the user's reply. whatever
    // is still over budget stays out of this prompt and gets its own recap on a later turn.
    private suspend fun conversationPlanForPrompt(scope: ConversationScope, tokenBudget: Int): ConversationPlan {
        val snapshot = conversation.load(scope)
        val plan = planFor(snapshot, tokenBudget)

        if (plan.compactablePrefix.isEmpty()) return plan

        val compacted =
            try {
                conversationCompactor.compact(snapshot.summary, plan.compactablePrefix)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn {
                    "history recap failed for $scope: " +
                            e.message?.collapseWhitespaceAndCap(PROVIDER_ERROR_LOG_MAX_CHARS).orEmpty()
                }
                return plan
            } ?: return plan

        val stored =
            conversation.storeSummary(
                scope = scope,
                expectedThroughMessageId = snapshot.summarizedThroughMessageId,
                throughMessageId = compacted.throughMessageId,
                content = compacted.summary
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

        return planFor(conversation.load(scope), tokenBudget)
    }

    private fun planFor(snapshot: ConversationSnapshot, tokenBudget: Int): ConversationPlan =
        planConversation(
            snapshot = snapshot,
            tokenBudget = tokenBudget,
            maxRecentInteractions = conversationConfig.maxRecentInteractions
        )

    private suspend fun runAgentWithConversation(
        scope: ConversationScope,
        currentTurn: String,
        conversation: PromptConversation,
        preparation: AgentPromptPreparation,
        outbox: BotOutbox,
        toolEvents: MutableList<ToolEvent>,
        tokenUsages: MutableList<TokenUsage>,
        onToolStarting: (activity: ToolActivity?) -> Unit
    ): String {
        suspend fun run(prompt: PromptConversation): String =
            agentFactory
                .build(
                    scope = scope,
                    conversation = prompt,
                    preparation = preparation,
                    outbox = outbox,
                    toolEvents = toolEvents::add,
                    tokenUsage = tokenUsages::add,
                    onToolStarting = onToolStarting
                )
                .run(currentTurn)

        return try {
            run(conversation)
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            val emergencyConversation =
                PromptConversation(
                    summary = conversation.summary?.limitTo(EMERGENCY_SUMMARY_MAX_CHARS),
                    turns = emptyList()
                )
            val safeToRetry =
                e.isContextOverflow() &&
                        conversation != emergencyConversation &&
                        toolEvents.isEmpty() &&
                        outbox.pending.isEmpty()

            if (!safeToRetry) throw e

            log.warn { "context limit exceeded for $scope; retrying once with recap only" }
            run(emergencyConversation)
        }
    }

    // the budget ran out with the turn already in flight. that is not a failure to retry — the turn is over
    // until the budget resets — so it gets the same "come back later" reply as a turn that never started.
    private fun budgetStopReply(context: RequestContext, e: Throwable): String? =
        e.tokenBudgetStop()
            ?.let { stop ->
                log.warn {
                    "token budget stop mid-turn: chat=${context.chat.id} user=${context.sender.id} " +
                            "reason=${stop::class.simpleName} resetsIn=${stop.untilReset}"
                }

                Messages.of(context.language).replyFor(stop)
            }

    // pick the user-facing reply and log accordingly. LLM provider errors arrive as a large JSON body, so
    // they get a single capped WARN line; a transient overload (429/503) gets a friendly "try again" reply,
    // any other provider error and genuine unexpected failures get the generic fallback (the latter with a
    // full stack trace, since it points at a real bug).
    private fun replyForAgentFailure(context: RequestContext, e: Throwable): String {
        val messages = Messages.of(context.language)

        // the ChatGPT session died mid-turn (expired, revoked, or reused refresh token). nothing the
        // user can do, and nothing to retry — say so plainly instead of the generic failure reply.
        generateSequence(e) { it.cause }.filterIsInstance<CodexAuthException>().firstOrNull()?.let { authError ->
            log.error { "codex auth failed for chat=${context.chat.id} user=${context.sender.id}: ${authError.message}" }
            return messages.signInRequiredReply
        }

        val providerError = e.providerErrorMessage()

        if (providerError == null) {
            log.error(e) { "agent.run failed for chat=${context.chat.id} user=${context.sender.id}" }
            return messages.fallbackErrorReply
        }

        log.warn {
            "agent.run provider error for chat=${context.chat.id} user=${context.sender.id}: " +
                    providerError.collapseWhitespaceAndCap(PROVIDER_ERROR_LOG_MAX_CHARS)
        }

        return messages.providerErrorReply(providerError)
    }

    private fun retainLock(key: ConversationScope): Mutex =
        synchronized(conversationLocks) {
            conversationLocks.getOrPut(key) { ConversationLock() }.also { it.refCount++ }.mutex
        }

    private fun releaseLock(key: ConversationScope) {
        synchronized(conversationLocks) {
            val entry = conversationLocks[key] ?: return
            if (--entry.refCount <= 0) conversationLocks.remove(key)
        }
    }

    private class ConversationLock(val mutex: Mutex = Mutex(), var refCount: Int = 0)
}

private fun Messages.replyFor(stop: TokenBudgetStop): String =
    when (stop) {
        is TokenBudgetStop.DayBudget -> tokenBudgetExhaustedReply(stop.untilReset)
        is TokenBudgetStop.UserShare -> tokenShareExhaustedReply(stop.untilReset)
    }

private fun tokenUsageLogSummary(usages: List<TokenUsage>): String {

    fun List<Int?>.sumOrNa(): String =
        filterNotNull().let { if (it.isEmpty()) "n/a" else it.sum().toString() }

    val inputs = usages.mapNotNull { it.inputTokens }
    val promptTokens = inputs.lastOrNull()

    return "calls=${usages.size} promptTokens=${promptTokens ?: "n/a"} " +
            "minPromptTokens=${inputs.minOrNull() ?: "n/a"} maxPromptTokens=${inputs.maxOrNull() ?: "n/a"} " +
            "inputTokens=${usages.map { it.inputTokens }.sumOrNa()} " +
            "outputTokens=${usages.map { it.outputTokens }.sumOrNa()} " +
            "runTotal=${usages.map { it.totalTokens }.sumOrNa()}"
}

private fun outputsLogSummary(outputs: List<OutboxItem>): String =
    outputs.joinToString(", ") { item ->
        when (val output = item.output) {
            is BotOutput.Reaction -> "reaction ${output.emoji}"
            is BotOutput.PhotoGroup -> "photoGroup(${output.photos.size})"
            is BotOutput.DocumentGroup -> "documentGroup(${output.documents.size})"
            is BotOutput.AudioGroup -> "audioGroup(${output.audios.size})"
            else -> output::class.simpleName ?: "?"
        }
    }
