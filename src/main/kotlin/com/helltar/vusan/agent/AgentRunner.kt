package com.helltar.vusan.agent

import ai.koog.prompt.executor.clients.LLMClientException
import com.helltar.vusan.agent.history.*
import com.helltar.vusan.agent.memory.MemoryEntry
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.MemoryScope
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.isEffectivelyBlank
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.config.ChatHistoryConfig
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.choice.InlineChoiceTools
import com.helltar.vusan.tools.message.MessageTools
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val TOOL_OUTPUT_MAX_CHARS = 4_000
private const val TOOL_EVENTS_MAX_COUNT = 8
private const val TOOL_EVENTS_MAX_CHARS = 12_000
private const val EMERGENCY_SUMMARY_MAX_CHARS = 1_500
private const val LOG_REPLY_MAX_CHARS = 300
private const val PROVIDER_ERROR_LOG_MAX_CHARS = 300

data class AgentRequest(
    val chatId: Long,
    val userId: Long,
    val messageId: Long,
    val replyToMessageId: Long? = null,
    val prompt: String,
    val historyEntry: String,
    val messageContext: MessageContext? = null,
    val chatIsPrivate: Boolean = false,
    val attachedFile: AttachedFile? = null,
    val language: Language = Language.DEFAULT
)

data class AgentResult(
    val outputs: List<OutboxItem>,
    val comment: String?,
    val commentToPrivate: Boolean = false
)

class AgentRunner(
    private val agentFactory: AgentFactory,
    private val history: ChatHistoryRepository,
    private val memory: MemoryRepository,
    private val conversationCompactor: ConversationCompactor,
    private val historyConfig: ChatHistoryConfig = ChatHistoryConfig()
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val userLocks = HashMap<Long, UserLock>()

    suspend fun handle(request: AgentRequest, onToolStarting: (activity: ToolActivity) -> Unit = {}): AgentResult {
        val lock = retainLock(request.userId)

        try {
            if (!lock.tryLock()) {
                return AgentResult(outputs = emptyList(), comment = Messages.of(request.language).busyReply)
            }

            try {
                return runAgent(request, onToolStarting)
            } finally {
                lock.unlock()
            }
        } finally {
            releaseLock(request.userId)
        }
    }

    suspend fun handleScheduled(request: AgentRequest): AgentResult =
        handleQueued(request)

    suspend fun handleQueued(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity) -> Unit = {}
    ): AgentResult {
        val lock = retainLock(request.userId)

        try {
            return lock.withLock { runAgent(request, onToolStarting) }
        } finally {
            releaseLock(request.userId)
        }
    }

    // a turn persists its own history under this lock, so an outside clear (the `/clear` command) has to
    // take the same lock or a turn already in flight would append itself back into the wiped history.
    // `clearChatHistory` runs inside a turn and must keep using the repository directly.
    suspend fun clearHistory(userId: Long) {
        val lock = retainLock(userId)

        try {
            lock.withLock { history.clear(userId) }
        } finally {
            releaseLock(userId)
        }
    }

    private suspend fun runAgent(request: AgentRequest, onToolStarting: (activity: ToolActivity) -> Unit = {}): AgentResult {
        val context =
            RequestContext(
                chatId = request.chatId,
                userId = request.userId,
                messageId = request.messageId,
                replyToMessageId = request.replyToMessageId,
                senderUsername = request.messageContext?.userUsername,
                senderDisplayName = request.messageContext?.userDisplayName,
                chatIsPrivate = request.messageContext?.isPrivate ?: request.chatIsPrivate,
                attachedFile = request.attachedFile,
                language = request.language
            )

        val userMemory = memory.load(MemoryScope.USER, request.userId)
        val chatMemory = if (context.chatIsPrivate) emptyList() else memory.load(MemoryScope.CHAT, request.chatId)

        // the turn is stored only after the run, so this still points at the previous exchange.
        val messageContext =
            request.messageContext?.copy(previousExchangeAt = history.lastInteractionAt(request.userId))

        val currentTurn = currentTurnPrompt(request.prompt, messageContext, userMemory, chatMemory)
        val outbox = BotOutbox()
        val preparation = agentFactory.prepare(context, outbox, currentTurn)
        val historyPlan = historyPlanForPrompt(request.userId, preparation.tokenBudget.historyTokens)
        val plannedInputTokens = preparation.tokenBudget.fixedPromptTokens + historyPlan.estimatedTokens

        log.info {
            "prompt history loaded: user=${request.userId} chat=${request.chatId} " +
                    "storedInteractions=${historyPlan.stats.storedInteractions} storedMessages=${historyPlan.stats.storedMessages} " +
                    "storedChars=${historyPlan.stats.storedChars} unsummarized=${historyPlan.stats.unsummarizedInteractions} " +
                    "includedInteractions=${historyPlan.includedInteractions} turns=${historyPlan.history.turns.size} " +
                    "summaryChars=${historyPlan.history.summary?.length ?: 0} exactToolInteractions=${historyPlan.exactToolInteractions} " +
                    "userMemory=${userMemory.size} chatMemory=${chatMemory.size} " +
                    "promptChars=${request.prompt.length} historyChars=${request.historyEntry.length} " +
                    "attachedFile=${request.attachedFile != null}"
        }

        log.info {
            "prompt context plan: user=${request.userId} chat=${request.chatId} " +
                    "contextTokens=${preparation.tokenBudget.contextWindowTokens} " +
                    "fixedTokens=${preparation.tokenBudget.fixedPromptTokens} " +
                    "historyBudget=${preparation.tokenBudget.historyTokens} " +
                    "historyTokens=${historyPlan.estimatedTokens} " +
                    "responseReserve=${preparation.tokenBudget.responseReserveTokens} " +
                    "agentReserve=${preparation.tokenBudget.agentReserveTokens} " +
                    "safetyReserve=${preparation.tokenBudget.safetyReserveTokens} " +
                    "plannedInputTokens=$plannedInputTokens " +
                    "contextPercent=${preparation.tokenBudget.contextPercentFor(historyPlan.estimatedTokens)}"
        }

        val toolEvents = mutableListOf<ToolEvent>()
        val tokenUsages = mutableListOf<TokenUsage>()

        val answer =
            try {
                runAgentWithHistory(
                    userId = request.userId,
                    currentTurn = currentTurn,
                    history = historyPlan.history,
                    preparation = preparation,
                    outbox = outbox,
                    toolEvents = toolEvents,
                    tokenUsages = tokenUsages,
                    onToolStarting = onToolStarting
                )
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                return AgentResult(outputs = emptyList(), comment = replyForAgentFailure(request, e))
            }

        log.info {
            "token usage: chat=${request.chatId} user=${request.userId} ${tokenUsageLogSummary(tokenUsages)}"
        }

        val outputs = outbox.pending
        val comment = extractFinalComment(answer, outputs)

        if (outputs.isEmpty() && comment.isNullOrBlank()) {
            log.info { "agent produced no output for chat=${request.chatId} user=${request.userId}; staying silent" }
            return AgentResult(outputs = emptyList(), comment = null)
        }

        val assistantText = assistantTextForHistory(outputs, comment)

        val historyTurns =
            buildHistoryTurns(
                userEntry = request.historyEntry,
                toolEvents = toolEvents,
                assistantText = assistantText
            )

        log.info {
            "agent reply: chat=${request.chatId} user=${request.userId} " +
                    "outputs=[${outputsLogSummary(outputs)}] " +
                    "text=[${assistantText?.collapseWhitespaceAndCap(LOG_REPLY_MAX_CHARS).orEmpty()}]"
        }

        if (historyTurns.isNotEmpty()) {
            history.appendInteraction(request.userId, historyTurns)
        }

        val pruned =
            history.pruneCompacted(
                userId = request.userId,
                maxStoredInteractions = historyConfig.maxStoredInteractions,
                rawRetentionCutoff = Instant.now().minus(historyConfig.retentionDays.toLong(), ChronoUnit.DAYS)
            )

        if (pruned > 0) {
            log.info { "history raw retention pruned: user=${request.userId} interactions=$pruned" }
        }

        return AgentResult(outputs, comment, outbox.redirectToPrivate)
    }

    // at most one recap per turn: it is an extra LLM round trip in front of the user's reply. whatever
    // is still over budget stays out of this prompt and gets its own recap on a later turn.
    private suspend fun historyPlanForPrompt(userId: Long, tokenBudget: Int): HistoryPromptPlan {
        val snapshot = history.load(userId)
        val plan = planFor(snapshot, tokenBudget)

        if (plan.compactablePrefix.isEmpty()) return plan

        val compacted =
            try {
                conversationCompactor.compact(snapshot.summary, plan.compactablePrefix)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn {
                    "history recap failed for user=$userId: " +
                            e.message?.collapseWhitespaceAndCap(PROVIDER_ERROR_LOG_MAX_CHARS).orEmpty()
                }
                return plan
            } ?: return plan

        val stored =
            history.storeSummary(
                userId = userId,
                expectedThroughMessageId = snapshot.summarizedThroughMessageId,
                throughMessageId = compacted.throughMessageId,
                content = compacted.summary
            )

        if (!stored) {
            log.warn { "history recap checkpoint changed before store for user=$userId; keeping the raw history" }
            return plan
        }

        log.info {
            "history recap stored: user=$userId interactions=${compacted.interactionCount} " +
                    "throughMessage=${compacted.throughMessageId} chars=${compacted.summary.length}"
        }

        return planFor(history.load(userId), tokenBudget)
    }

    private fun planFor(snapshot: ChatHistorySnapshot, tokenBudget: Int): HistoryPromptPlan =
        planHistoryForPrompt(
            snapshot = snapshot,
            tokenBudget = tokenBudget,
            maxRecentInteractions = historyConfig.maxRecentInteractions
        )

    private suspend fun runAgentWithHistory(
        userId: Long,
        currentTurn: String,
        history: PromptHistory,
        preparation: AgentPromptPreparation,
        outbox: BotOutbox,
        toolEvents: MutableList<ToolEvent>,
        tokenUsages: MutableList<TokenUsage>,
        onToolStarting: (activity: ToolActivity) -> Unit
    ): String {
        suspend fun run(promptHistory: PromptHistory): String =
            agentFactory
                .build(
                    userId = userId,
                    history = promptHistory,
                    preparation = preparation,
                    outbox = outbox,
                    toolEvents = toolEvents::add,
                    tokenUsage = tokenUsages::add,
                    onToolStarting = onToolStarting
                )
                .run(currentTurn)

        return try {
            run(history)
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            val emergencyHistory =
                PromptHistory(
                    summary = history.summary?.limitTo(EMERGENCY_SUMMARY_MAX_CHARS),
                    turns = emptyList()
                )
            val safeToRetry =
                e.isContextOverflow() &&
                        history != emergencyHistory &&
                        toolEvents.isEmpty() &&
                        outbox.pending.isEmpty()

            if (!safeToRetry) throw e

            log.warn { "context limit exceeded for user=$userId; retrying once with recap only" }
            run(emergencyHistory)
        }
    }

    // pick the user-facing reply and log accordingly. LLM provider errors arrive as a large JSON body, so
    // they get a single capped WARN line; a transient overload (429/503) gets a friendly "try again" reply,
    // any other provider error and genuine unexpected failures get the generic fallback (the latter with a
    // full stack trace, since it points at a real bug).
    private fun replyForAgentFailure(request: AgentRequest, e: Throwable): String {
        val messages = Messages.of(request.language)
        val providerError = generateSequence(e) { it.cause }.filterIsInstance<LLMClientException>().firstOrNull()

        if (providerError == null) {
            log.error(e) { "agent.run failed for chat=${request.chatId} user=${request.userId}" }
            return messages.fallbackErrorReply
        }

        log.warn {
            "agent.run provider error for chat=${request.chatId} user=${request.userId}: " +
                    providerError.message?.collapseWhitespaceAndCap(PROVIDER_ERROR_LOG_MAX_CHARS).orEmpty()
        }

        return if (providerError.isTransientOverload()) messages.overloadedReply else messages.fallbackErrorReply
    }

    private fun retainLock(userId: Long): Mutex =
        synchronized(userLocks) {
            userLocks.getOrPut(userId) { UserLock() }.also { it.refCount++ }.mutex
        }

    private fun releaseLock(userId: Long) {
        synchronized(userLocks) {
            val entry = userLocks[userId] ?: return
            if (--entry.refCount <= 0) userLocks.remove(userId)
        }
    }

    private class UserLock(val mutex: Mutex = Mutex(), var refCount: Int = 0)
}

internal fun currentTurnPrompt(
    userInput: String,
    messageContext: MessageContext?,
    userMemory: List<MemoryEntry>,
    chatMemory: List<MemoryEntry>
): String =
    buildList {
        messageContext?.toPromptBlock()?.let(::add)
        memoryBlock("user_memory", userMemory)?.let(::add)
        memoryBlock("group_memory", chatMemory)?.let(::add)
        add(userInput)
    }.joinToString("\n\n")

// renders memory as `#id content` lines so the model can reference an id when calling `forgetMemory`.
private fun memoryBlock(
    tag: String,
    entries: List<MemoryEntry>
): String? =
    entries
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "#${it.id} ${it.content}" }
        ?.let { xmlBlock(tag, it) }

// the provider's HTTP status is embedded in the client exception message ("Status code: 429").
// 429 (rate limit / quota) and 503 (service overloaded) are transient — the provider asks us to back off.
private val TRANSIENT_STATUS_REGEX = Regex("""Status code:\s*(429|503)""")
private val CONTEXT_OVERFLOW_REGEX =
    Regex(
        "context[_ ]length|context window|maximum context|too many (input )?tokens|" +
                "prompt is too long|input is too long",
        RegexOption.IGNORE_CASE
    )

private fun LLMClientException.isTransientOverload(): Boolean =
    message?.let { TRANSIENT_STATUS_REGEX.containsMatchIn(it) } == true

private fun Throwable.isContextOverflow(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<LLMClientException>()
        .any { error -> error.message?.let(CONTEXT_OVERFLOW_REGEX::containsMatchIn) == true }

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
            else -> output::class.simpleName ?: "?"
        }
    }

private fun extractFinalComment(answer: String, outputs: List<OutboxItem>): String? =
    answer.trim()
        .takeUnless { it.isEffectivelyBlank() }
        ?.takeUnless {
            outputs.any {
                it.output is BotOutput.Voice ||
                        it.output is BotOutput.VideoNote ||
                        it.output is BotOutput.Text ||
                        it.output is BotOutput.RichMessage ||
                        it.output is BotOutput.InlineChoice ||
                        it.output is BotOutput.Reaction
            }
        }

internal fun assistantTextForHistory(outputs: List<OutboxItem>, comment: String?): String? {
    val parts =
        buildList {
            addAll(
                outputs.mapNotNull {
                    when (val output = it.output) {
                        is BotOutput.Text -> output.text
                        is BotOutput.InlineChoice -> output.historyText()
                        is BotOutput.RichMessage -> output.markdown
                        else -> null
                    }
                }
            )
            comment?.let(::add)
        }

    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

// tools whose payload is fully duplicated by the assistant text row. skipping their
// matching TOOL_CALL/TOOL_RESULT pair avoids storing (and replaying) the same content twice.
// the Koog runtime registers each tool under its function name (no tool here sets @Tool(customName)),
// so a function reference stays in sync with the registered name across renames.
private val TEXT_DUPLICATING_TOOLS =
    setOf(
        MessageTools::sendMessage.name,
        MessageTools::sendRichMessage.name,
        InlineChoiceTools::askWithButtons.name
    )

private fun BotOutput.InlineChoice.historyText(): String =
    question + "\n\n" + options.joinToString("\n") { "• $it" }

internal fun buildHistoryTurns(userEntry: String, toolEvents: List<ToolEvent>, assistantText: String?): List<ChatTurn> =
    buildList {
        add(ChatTurn(role = ChatRole.USER, content = userEntry))

        for (event in toolEvents.forHistory()) {

            add(
                ChatTurn(
                    role = ChatRole.TOOL_CALL,
                    content = toolCallArgsForHistory(event.args),
                    toolCallId = event.toolCallId,
                    toolName = event.toolName
                )
            )

            add(
                ChatTurn(
                    role = ChatRole.TOOL_RESULT,
                    content = event.output.collapseWhitespaceAndCap(TOOL_OUTPUT_MAX_CHARS).orEmpty(),
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    toolIsError = event.isError
                )
            )
        }

        if (!assistantText.isNullOrBlank()) {
            add(ChatTurn(role = ChatRole.ASSISTANT, content = assistantText))
        }
    }

private fun List<ToolEvent>.forHistory(): List<ToolEvent> {
    val selected = ArrayDeque<ToolEvent>()
    var usedChars = 0

    for (event in asReversed()) {
        if (event.toolName in TEXT_DUPLICATING_TOOLS) continue

        val args = toolCallArgsForHistory(event.args)
        val output = event.output.collapseWhitespaceAndCap(TOOL_OUTPUT_MAX_CHARS).orEmpty()
        val cost = args.length + output.length

        if (selected.isNotEmpty() && (selected.size >= TOOL_EVENTS_MAX_COUNT || usedChars + cost > TOOL_EVENTS_MAX_CHARS)) {
            continue
        }

        selected.addFirst(event)
        usedChars += cost
    }

    return selected.toList()
}
