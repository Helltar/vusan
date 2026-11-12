package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.*
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.memoryOwner
import com.helltar.vusan.llm.Message
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
import com.helltar.vusan.tools.grouplog.GroupLogTools
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    // what the chat adds to a turn's prompt: the diary, the recent chat, the sticker shortlist
    private val surroundings: TurnSurroundings = TurnSurroundings(),
    // the answering model when it is not the one the system prompt names, so a fallback turn does not claim
    // to be the primary.
    private val fallbackModelInUse: () -> String? = { null },
    // the ceiling all conversations share; each turn is an LLM call plus tools. no default, so serving one
    // turn at a time is never discovered under load.
    maxConcurrentTurns: Int,
    // how long one person may keep a group to themselves; shared with the adapter, which reports the chat's
    // other lines
    private val floor: ChatFloor = ChatFloor(),
) {

    private val planner = ConversationPlanner(conversation, conversationCompactor)
    private val admission = TurnAdmission(maxConcurrentTurns)

    // the last request of each conversation, for providers that read back only what a request repeats
    private val sentConversations = SentConversations().takeIf { agentFactory.continuesLastRequest }

    // the lock is always taken before a place: a turn holding a place is running and waits for nothing, so no
    // cycle, and one person's lines cost others no places.
    private val conversationLocks = ConversationLocks<ConversationScope>(MAX_QUEUED_TURNS_PER_CONVERSATION)
    private val running = RunningTurns<ConversationScope>()

    suspend fun handle(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        narrator: TurnNarrator? = null,
    ): AgentResult {
        val key = request.context.scope
        val messages = Messages.of(request.context.language)

        // an untagged message may not have been for the bot, so it is turned away silently: "hold on" in
        // someone else's conversation is worse than nothing.
        fun refusal(reply: String): AgentResult {
            if (!request.context.ambient) return AgentResult(outputs = emptyList(), comment = reply)

            log.info {
                "ambient message turned away unanswered: chat=${request.context.chat.id} user=${request.context.sender.id}"
            }

            return AgentResult(outputs = emptyList(), comment = null)
        }

        // decided before queuing, so an unanswered message holds no place. silent whoever sent it: the
        // stretch's last answer already said so.
        val verdict = floor.admit(request.context)
        val lastAnswerOfStretch = verdict == ChatFloor.Verdict.LAST_ANSWER

        if (verdict == ChatFloor.Verdict.CLOSED) {
            log.info {
                "floor closed: left unanswered, this sender alone has had the chat for a while; " +
                        "chat=${request.context.chat.id} user=${request.context.sender.id}"
            }

            return AgentResult(outputs = emptyList(), comment = null)
        }

        // counted before queuing too, so a turn that ends without answering (refused, silent, failed,
        // stopped) gives its count back, and a stretch never closes on an answer nobody got
        var answered = false

        // tracked from the queue on, so a stop also cancels the person's waiting messages instead of letting
        // the next start.
        val result =
            try {
                running.track(key) {
                    conversationLocks.withLockIfRoom(key) {
                        admission.admit {
                            runAgent(request, onToolStarting, narrator, lastAnswerOfStretch)
                                .also { answered = it.answeredInChat }
                        } ?: refusal(messages.overloadedReply)
                    }
                }
            } finally {
                if (!answered) floor.unanswered(request.context)
            }

        return result ?: refusal(messages.busyReply)
    }

    /** Starts the worker that recaps a conversation once a turn left it longer than it keeps raw. */
    fun launchRecapsIn(scope: CoroutineScope): Job =
        planner.launchIn(scope)

    /** Whether this conversation has a turn running or queued. */
    fun hasTurnUnderWay(scope: ConversationScope): Boolean =
        running.holds(scope)

    /** Whether anyone in this chat has a turn running or queued. */
    fun hasTurnUnderWayIn(chat: ChatRef): Boolean =
        running.holdsAny { it.chat == chat }

    /**
     * Cancels the running turn and the person's queued messages, and reports whether there was anything. The
     * model call, the tool and whatever it started are children of one job. A sandbox command outlives it on
     * its machine, bounded by its own timeout, and the model can list and cancel those.
     */
    fun stop(scope: ConversationScope): Boolean =
        running.cancel(scope)

    suspend fun handleQueued(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        narrator: TurnNarrator? = null,
    ): AgentResult {
        val key = request.context.scope

        // a queued turn waits for its place instead of being refused, and takes it after the lock as in
        // `handle`.
        return conversationLocks.withLock(key) {
            admission.admitQueued { running.track(key) { runAgent(request, onToolStarting, narrator) } }
        }
    }

    // a turn persists its history under this lock, so `/clear` must take it too or an in-flight turn would
    // append itself back. keyed per conversation, so the same person in two chats is served in both.
    suspend fun clearConversation(scope: ConversationScope) {
        conversationLocks.withLock(scope) {
            conversation.clear(scope)
            sentConversations?.forget(scope)
        }
    }

    private suspend fun runAgent(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity?) -> Unit,
        narrator: TurnNarrator?,
        lastAnswerOfStretch: Boolean = false,
    ): AgentResult {
        val context = request.context
        val userMemory = if (context.sender.isPerson) memory.load(context.user.memoryOwner) else emptyList()
        val chatMemory = if (context.chat.isPrivate) emptyList() else memory.load(context.chatRef.memoryOwner)

        // what a continued request must have been sent with, since each of its messages carries its own
        // turn's
        val memoryFingerprint = (userMemory to chatMemory).hashCode()

        // read before building, so a log erased during this turn keeps its request from being kept
        val erasuresSeen = sentConversations?.erasuresOf(context.chatRef) ?: 0

        val outbox = BotOutbox(context.chat.capabilities)
        if (request.replyPrivately) outbox.useDirectMessages()

        val toolBudget = TurnToolBudget(agentFactory.liveToolResultMaxTokens)
        val shelf = TurnShelf(context.attachedFiles)
        val toolCatalog = toolCatalogFactory.buildCatalog(context, outbox, toolBudget, shelf, narrator)

        val currentTurn =
            currentTurnPrompt(
                userInput = request.prompt,
                context = context,
                // the turn is stored after the run, so this still points at the previous exchange.
                previousExchangeAt = conversation.lastInteractionAt(context.scope),
                lastAnswerOfStretch = lastAnswerOfStretch,
                userMemory = userMemory,
                chatMemory = chatMemory,
                leftAloneUntil = surroundings.leftAloneUntil(context),
                diary = surroundings.diaryFor(context),
                recentChat = surroundings.recentChatFor(context),
                stickerCatalog = surroundings.stickerCatalogFor(context),
                toolGroups = toolCatalog.menu(),
                fallbackModel = fallbackModelInUse(),
            )

        val preparation = agentFactory.prepare(toolCatalog, currentTurn)

        val conversationPlan = planner.planForPrompt(context.scope, preparation.tokenBudget.conversationTokens)

        val continued =
            sentConversations?.continuation(
                scope = context.scope,
                systemPrompt = preparation.systemPrompt,
                newestStoredInteractionId = conversationPlan.newestInteractionId,
                memoryFingerprint = memoryFingerprint,
                storedHistoryTokens = conversationPlan.estimatedTokens,
                historyBudget = preparation.tokenBudget.conversationTokens,
                rawRetentionCutoff = rawRetentionCutoff(),
            )

        // the labels of the continued request are in it, so this turn's numbering carries on
        continued?.let { shelf.numberFrom(it.nextCall) }

        val historyTokens = continued?.historyTokens ?: conversationPlan.estimatedTokens
        val plannedInputTokens = preparation.tokenBudget.fixedPromptTokens + historyTokens

        log.info {
            "prompt history loaded: user=${context.sender.id} chat=${context.chat.id} " +
                    "cacheKey=[${agentFactory.conversationCacheKey(context.scope) ?: "none"}] " +
                    "storedInteractions=${conversationPlan.stats.storedInteractions} storedMessages=${conversationPlan.stats.storedMessages} " +
                    "storedChars=${conversationPlan.stats.storedChars} unsummarized=${conversationPlan.stats.unsummarizedInteractions} " +
                    "includedInteractions=${conversationPlan.includedInteractions} turns=${conversationPlan.prompt.turns.size} " +
                    "summaryChars=${conversationPlan.prompt.summary?.length ?: 0} exactToolInteractions=${conversationPlan.exactToolInteractions} " +
                    "continued=${continued?.let { "[${it.messages.size} messages]" } ?: "[no]"} " +
                    "userMemory=${userMemory.size} chatMemory=${chatMemory.size} " +
                    "promptChars=${request.prompt.length} historyChars=${request.conversationEntry.length} " +
                    "attachedFiles=${context.attachedFiles.size}"
        }

        log.info {
            "prompt context plan: user=${context.sender.id} chat=${context.chat.id} " +
                    "contextTokens=${preparation.tokenBudget.contextWindowTokens} " +
                    "fixedTokens=${preparation.tokenBudget.fixedPromptTokens} " +
                    "historyBudget=${preparation.tokenBudget.conversationTokens} " +
                    "conversationTokens=$historyTokens " +
                    "responseReserve=${preparation.tokenBudget.responseReserveTokens} " +
                    "agentReserve=${preparation.tokenBudget.agentReserveTokens} " +
                    "safetyReserve=${preparation.tokenBudget.safetyReserveTokens} " +
                    "plannedInputTokens=$plannedInputTokens " +
                    "contextPercent=${preparation.tokenBudget.contextPercentFor(historyTokens)}"
        }

        val toolEvents = mutableListOf<ToolEvent>()
        val tokenUsages = mutableListOf<TokenUsage>()

        // every request kept for the chat carries what it said, so a cleared log drops them at once
        fun recordToolEvent(event: ToolEvent) {
            toolEvents += event
            if (event.toolName == GroupLogTools::clearGroupLog.name && !event.isError) sentConversations?.forgetChat(context.chatRef)
        }
        // what the turn's last request held, once the run returned
        var sent: List<Message> = emptyList()

        val answer =
            try {
                runAgentWithConversation(
                    scope = context.scope,
                    currentTurn = currentTurn,
                    conversation = conversationPlan.prompt,
                    continued = continued?.messages,
                    preparation = preparation,
                    outbox = outbox,
                    toolBudget = toolBudget,
                    toolEvents = toolEvents,
                    recordToolEvent = ::recordToolEvent,
                    tokenUsages = tokenUsages,
                    onToolStarting = onToolStarting,
                    mayStaySilent = context.ambient,
                    shelf = shelf,
                    sent = { sent = it },
                )
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                // logs the cause either way; the reply is used only when nothing was delivered.
                val failureReply = replyForAgentFailure(context, e)

                // an announced plan is a promise, not an answer, so a turn with nothing else owes the reason
                // it stopped; otherwise the chat sees "building it now…" followed by silence.
                if (!outbox.hasAnswered) {
                    return AgentResult(outputs = outbox.pending, comment = failureReply, failed = true)
                }

                // an answer is already queued or in the chat: keep it instead of the canned failure, and
                // finish normally so the work reaches history and a scheduled task counts the attempt as
                // delivered.
                log.warn {
                    "agent.run failed after partial delivery for chat=${context.chat.id} user=${context.sender.id}; " +
                            "finishing with what it already answered, ${outbox.pending.size} output(s) still queued"
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
                        (if (context.ambient) " ambient=[true]" else "") +
                        outbox.silence?.let { " why=[${it.collapseWhitespaceAndCap(LOG_REPLY_MAX_CHARS)}]" }.orEmpty()
            }

            // a declared silence is an answer, kept with its message so the next turn sees it was asked to be
            // quiet. a turn that merely delivered nothing (a message not for it) leaves no trace
            if (outbox.silence == null) return AgentResult(outputs = emptyList(), comment = null)
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
            val stored = conversation.appendInteraction(context.scope, turns)
            planner.recapLater(context.scope, preparation.tokenBudget.conversationTokens)

            // a run that threw left nothing to continue, and its stored history is not in the kept request
            sentConversations?.let { kept ->
                if (stored != null && sent.isNotEmpty()) {
                    val oldestTurnAt = continued?.oldestTurnAt ?: conversationPlan.oldestIncludedAt ?: Instant.now()
                    kept.remember(context.scope, SentConversations.Sent(sent, stored, shelf.nextNumber, memoryFingerprint, oldestTurnAt), erasuresSeen)
                } else {
                    kept.forget(context.scope)
                }
            }
        }

        val pruned =
            conversation.pruneCompacted(
                scope = context.scope,
                maxStoredInteractions = ConversationRepository.MAX_STORED_INTERACTIONS,
                rawRetentionCutoff = rawRetentionCutoff(),
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
        continued: List<Message>?,
        preparation: AgentPromptPreparation,
        outbox: BotOutbox,
        toolBudget: TurnToolBudget,
        toolEvents: MutableList<ToolEvent>,
        recordToolEvent: (ToolEvent) -> Unit,
        tokenUsages: MutableList<TokenUsage>,
        onToolStarting: (activity: ToolActivity?) -> Unit,
        mayStaySilent: Boolean,
        shelf: TurnShelf,
        sent: (List<Message>) -> Unit,
    ): String {
        suspend fun run(prompt: PromptConversation, continued: List<Message>?): String {
            val turn =
                agentFactory.build(
                    scope = scope,
                    conversation = prompt,
                    continued = continued,
                    preparation = preparation,
                    outbox = outbox,
                    toolBudget = toolBudget,
                    toolEvents = recordToolEvent,
                    tokenUsage = tokenUsages::add,
                    onToolStarting = onToolStarting,
                    mayStaySilent = mayStaySilent,
                    shelf = shelf,
                )

            return turn.run(currentTurn).also { sent(turn.sent) }
        }

        return try {
            run(conversation, continued)
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            val emergencyConversation =
                PromptConversation(
                    summary = conversation.summary?.limitTo(EMERGENCY_SUMMARY_MAX_CHARS),
                    turns = emptyList(),
                )
            val safeToRetry =
                e.isContextOverflow() &&
                        (continued != null || conversation != emergencyConversation) &&
                        toolEvents.isEmpty() &&
                        outbox.pending.isEmpty()

            if (!safeToRetry) throw e

            log.warn { "context limit exceeded for $scope; retrying once with recap only" }
            run(emergencyConversation, continued = null)
        }
    }

    private fun rawRetentionCutoff(): Instant = Instant.now().minus(conversationConfig.retentionDays.toLong(), ChronoUnit.DAYS)

    // picks the user-facing reply and logs. provider errors arrive as large JSON, so one capped WARN line; a
    // transient overload (429/503) gets "try again", anything else the generic fallback, with a full trace
    // for unexpected failures since they point at bugs.
    private fun replyForAgentFailure(context: RequestContext, e: Throwable): String {
        val messages = Messages.of(context.language)

        // the ChatGPT session died mid-turn (expired, revoked, reused refresh token): nothing to retry, so
        // say so plainly.
        generateSequence(e) { it.cause }.filterIsInstance<CodexAuthException>().firstOrNull()?.let { authError ->
            log.error { "codex auth failed for chat=${context.chat.id} user=${context.sender.id}: ${authError.message}" }
            return messages.signInRequiredReply
        }

        // a refusal is an answer, not an error, so there is no provider error to read
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

        // how many turns may wait behind a running one before the next is told to hold on.
        const val MAX_QUEUED_TURNS_PER_CONVERSATION = 3
    }
}

// whether the turn put a line of its own in the chat: a reaction adds none, and a failed run's canned reply
// is no answer
private val AgentResult.answeredInChat: Boolean
    get() = !failed && (!comment.isNullOrBlank() || outputs.any { it.output !is BotOutput.Reaction })

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
            "cacheWriteLongTokens=${usages.map { it.cacheWriteLongTokens }.sumOrNa()} " +
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
