package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.*
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.memoryOwner
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.codex.CodexAuthException
import com.helltar.vusan.config.ConversationConfig
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.ToolCatalogFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val EMERGENCY_SUMMARY_MAX_CHARS = 1_500
private const val LOG_REPLY_MAX_CHARS = 300
private const val PROVIDER_ERROR_LOG_MAX_CHARS = 300

class AgentRunner(
    private val agentFactory: AgentFactory,
    private val toolCatalogFactory: ToolCatalogFactory,
    private val conversation: ConversationRepository,
    private val memory: MemoryRepository,
    conversationCompactor: ConversationCompactor,
    private val conversationConfig: ConversationConfig = ConversationConfig(),
    // what the chat around a turn adds to its prompt: the diary, the recent chat, the sticker shortlist
    private val surroundings: TurnSurroundings = TurnSurroundings(),
    // which model is answering when it is not the one the system prompt names, so a turn served by the
    // fallback provider does not claim to be the primary.
    private val fallbackModelInUse: () -> String? = { null },
    // the ceiling every conversation shares: one person's lock says nothing about how many people may
    // be served at once, and each turn is an LLM call with its tools behind it. no default — a runner
    // quietly serving one turn at a time is not something to discover under load.
    maxConcurrentTurns: Int,
) {

    private val planner = ConversationPlanner(conversation, conversationCompactor)
    private val admission = TurnAdmission(maxConcurrentTurns)

    // the lock is taken before a place, in every path: a turn that holds a place is running and waits
    // for nothing, so nothing can wait in a circle — and a person's line costs the others no places.
    private val conversationLocks = ConversationLocks<ConversationScope>(MAX_QUEUED_TURNS_PER_CONVERSATION)
    private val running = RunningTurns<ConversationScope>()

    suspend fun handle(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        narrator: TurnNarrator? = null,
    ): AgentResult {
        val key = request.context.scope
        val messages = Messages.of(request.context.language)

        // a message nobody tagged the bot in may not have been meant for it at all, so it is turned away
        // in silence: "hold on" dropped into someone else's conversation is the one reply worse than none.
        fun refusal(reply: String): AgentResult {
            if (!request.context.ambient) return AgentResult(outputs = emptyList(), comment = reply)

            log.info {
                "ambient message turned away unanswered: chat=${request.context.chat.id} user=${request.context.sender.id}"
            }

            return AgentResult(outputs = emptyList(), comment = null)
        }

        // tracked from the line on, so a stop takes the person's waiting messages along with the one
        // that is running instead of letting the next of them start.
        val result =
            running.track(key) {
                conversationLocks.withLockIfRoom(key) {
                    admission.admit { runAgent(request, onToolStarting, narrator) }
                        ?: refusal(messages.overloadedReply)
                }
            }

        return result ?: refusal(messages.busyReply)
    }

    /** Whether this conversation has a turn running or waiting in its line. */
    fun hasTurnUnderWay(scope: ConversationScope): Boolean =
        running.holds(scope)

    /** Whether anyone in this chat has a turn running or waiting in their line. */
    fun hasTurnUnderWayIn(chat: ChatRef): Boolean =
        running.holdsAny { it.chat == chat }

    /**
     * Cancels what this conversation has under way — the turn it is running and the person's messages
     * waiting behind it — and reports whether there was anything. What the turn happened to be doing does
     * not matter: the model call, the tool it is inside and everything that tool started are children of
     * the same job. A sandbox command outlives it on its own machine, bounded by its own timeout, and the
     * model can list and cancel those separately.
     */
    fun stop(scope: ConversationScope): Boolean =
        running.cancel(scope)

    suspend fun handleScheduled(request: AgentRequest): AgentResult =
        handleQueued(request)

    suspend fun handleQueued(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        narrator: TurnNarrator? = null,
    ): AgentResult {
        val key = request.context.scope

        // a queued turn waits for its place rather than being turned away, and takes it after the
        // conversation lock for the same reason `handle` does.
        return conversationLocks.withLock(key) {
            admission.admitQueued { running.track(key) { runAgent(request, onToolStarting, narrator) } }
        }
    }

    // a turn persists its own history under this lock, so an outside clear (the `/clear` command) has to
    // take the same lock or a turn already in flight would append itself back into the wiped history.
    // the lock is keyed by what it guards, one conversation, so the same person writing in two chats is
    // served in both instead of being told the bot is busy.
    // `clearConversation` runs inside a turn and must keep using the repository directly.
    suspend fun clearConversation(scope: ConversationScope) {
        conversationLocks.withLock(scope) { conversation.clear(scope) }
    }

    private suspend fun runAgent(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit,
        narrator: TurnNarrator?,
    ): AgentResult {
        val context = request.context
        val userMemory = if (context.sender.isPerson) memory.load(context.user.memoryOwner) else emptyList()
        val chatMemory = if (context.chat.isPrivate) emptyList() else memory.load(context.chatRef.memoryOwner)

        val outbox = BotOutbox(context.chat.capabilities)
        val toolBudget = TurnToolBudget(agentFactory.liveToolResultMaxTokens)
        val toolCatalog = toolCatalogFactory.buildCatalog(context, outbox, toolBudget, narrator)

        val currentTurn =
            currentTurnPrompt(
                userInput = request.prompt,
                context = context,
                // the turn is stored only after the run, so this still points at the previous exchange.
                previousExchangeAt = conversation.lastInteractionAt(context.scope),
                userMemory = userMemory,
                chatMemory = chatMemory,
                diary = surroundings.diaryFor(context),
                recentChat = surroundings.recentChatFor(context),
                stickerCatalog = surroundings.stickerCatalogFor(context),
                toolGroups = toolCatalog.menu(),
                fallbackModel = fallbackModelInUse(),
            )

        val preparation = agentFactory.prepare(toolCatalog, currentTurn)

        val conversationPlan = planner.planForPrompt(context.scope, preparation.tokenBudget.conversationTokens)
        val plannedInputTokens = preparation.tokenBudget.fixedPromptTokens + conversationPlan.estimatedTokens

        log.info {
            "prompt history loaded: user=${context.sender.id} chat=${context.chat.id} " +
                    "cacheKey=[${agentFactory.conversationCacheKey(context.scope) ?: "none"}] " +
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
                    toolBudget = toolBudget,
                    toolEvents = toolEvents,
                    tokenUsages = tokenUsages,
                    onToolStarting = onToolStarting,
                    mayStaySilent = context.ambient,
                )
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
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
            log.info {
                "agent produced no output for chat=${context.chat.id} user=${context.sender.id}; staying silent" +
                        if (context.ambient) " ambient=[true]" else ""
            }

            return AgentResult(outputs = emptyList(), comment = null)
        }

        val assistantText = assistantTextForHistory(outputs, comment)

        val turns =
            buildTurns(
                userEntry = request.conversationEntry,
                toolEvents = toolEvents,
                assistantText = assistantText,
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
                maxStoredInteractions = ConversationRepository.MAX_STORED_INTERACTIONS,
                rawRetentionCutoff = Instant.now().minus(conversationConfig.retentionDays.toLong(), ChronoUnit.DAYS),
            )

        if (pruned > 0) {
            log.info { "history raw retention pruned: user=${context.sender.id} interactions=$pruned" }
        }

        return AgentResult(outputs, comment, outbox.redirectToPrivate)
    }

    private suspend fun runAgentWithConversation(
        scope: ConversationScope,
        currentTurn: String,
        conversation: PromptConversation,
        preparation: AgentPromptPreparation,
        outbox: BotOutbox,
        toolBudget: TurnToolBudget,
        toolEvents: MutableList<ToolEvent>,
        tokenUsages: MutableList<TokenUsage>,
        onToolStarting: (activity: ToolActivity?) -> Unit,
        mayStaySilent: Boolean,
    ): String {
        suspend fun run(prompt: PromptConversation): String =
            agentFactory
                .build(
                    scope = scope,
                    conversation = prompt,
                    preparation = preparation,
                    outbox = outbox,
                    toolBudget = toolBudget,
                    toolEvents = toolEvents::add,
                    tokenUsage = tokenUsages::add,
                    onToolStarting = onToolStarting,
                    mayStaySilent = mayStaySilent,
                )
                .run(currentTurn)

        return try {
            run(conversation)
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            val emergencyConversation =
                PromptConversation(
                    summary = conversation.summary?.limitTo(EMERGENCY_SUMMARY_MAX_CHARS),
                    turns = emptyList(),
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

        // a refusal comes back as an answer, not an error, so it carries no provider error to read
        generateSequence(e) { it.cause }.filterIsInstance<ModelRefusal>().firstOrNull()?.let { refusal ->
            log.warn { "model declined the request for chat=${context.chat.id} user=${context.sender.id}: reason=[${refusal.reason.orEmpty()}]" }
            return messages.contentPolicyReply
        }

        val providerError = e.providerError()

        if (providerError == null) {
            log.error(e) { "agent.run failed for chat=${context.chat.id} user=${context.sender.id}" }
            return messages.fallbackErrorReply
        }

        log.warn {
            "agent.run provider error for chat=${context.chat.id} user=${context.sender.id}: " +
                    providerError.message?.collapseWhitespaceAndCap(PROVIDER_ERROR_LOG_MAX_CHARS)
        }

        return messages.providerErrorReply(providerError)
    }

    private companion object {
        val log = KotlinLogging.logger {}

        // how many turns may wait behind the one a conversation is running before the next is told to
        // hold on.
        const val MAX_QUEUED_TURNS_PER_CONVERSATION = 3
    }
}

private fun tokenUsageLogSummary(usages: List<TokenUsage>): String {

    fun List<Int?>.sumOrNa(): String =
        filterNotNull().let { if (it.isEmpty()) "n/a" else it.sum().toString() }

    val inputs = usages.mapNotNull { it.inputTokens }
    val promptTokens = inputs.lastOrNull()

    return "calls=${usages.size} promptTokens=${promptTokens ?: "n/a"} " +
            "minPromptTokens=${inputs.minOrNull() ?: "n/a"} maxPromptTokens=${inputs.maxOrNull() ?: "n/a"} " +
            "inputTokens=${usages.map { it.inputTokens }.sumOrNa()} " +
            "cacheReadTokens=${usages.map { it.cacheReadTokens }.sumOrNa()} " +
            "cacheWriteTokens=${usages.map { it.cacheWriteTokens }.sumOrNa()} " +
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
