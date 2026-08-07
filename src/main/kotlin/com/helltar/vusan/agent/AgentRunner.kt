package com.helltar.vusan.agent

import ai.koog.prompt.executor.clients.LLMClientException
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.grouplog.renderGroupLog
import com.helltar.vusan.agent.grouplog.withoutExchangesWith
import com.helltar.vusan.agent.conversation.*
import com.helltar.vusan.agent.memory.MemoryEntry
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.MemoryScope
import com.helltar.vusan.budget.TokenBudget
import com.helltar.vusan.budget.tokenBudgetStop
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.isEffectivelyBlank
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.config.ConversationConfig
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.choice.InlineChoiceTools
import com.helltar.vusan.tools.message.MessageTools
import com.helltar.vusan.tools.sticker.StickerCatalog
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// `<recent_chat>` rides along on every group turn, so it is budgeted for cheapness, not for detail:
// enough to know what is being talked about, never enough to answer a recap question on its own.
private const val RECENT_CHAT_MAX_CHARS = 1_000
private const val RECENT_CHAT_LINE_CHARS = 120
private const val RECENT_CHAT_OVERFETCH = 3

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
    val conversationEntry: String,
    val messageContext: MessageContext? = null,
    val chatIsPrivate: Boolean = false,
    val attachedFile: AttachedFile? = null,
    val language: Language = Language.DEFAULT
)

data class AgentResult(
    val outputs: List<OutboxItem>,
    val comment: String?,
    val commentToPrivate: Boolean = false,
    // the run ended in an error and produced nothing: `comment` is the canned failure reply, not an answer.
    val failed: Boolean = false
)

class AgentRunner(
    private val agentFactory: AgentFactory,
    private val conversation: ConversationRepository,
    private val memory: MemoryRepository,
    private val conversationCompactor: ConversationCompactor,
    private val conversationConfig: ConversationConfig = ConversationConfig(),
    private val stickers: StickerCatalog? = null,
    private val groupLog: GroupLogRepository? = null,
    private val groupLogConfig: GroupLogConfig = GroupLogConfig(),
    private val tokenBudget: TokenBudget = TokenBudget()
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val conversationLocks = HashMap<ConversationKey, ConversationLock>()

    suspend fun handle(request: AgentRequest, onToolStarting: (activity: ToolActivity) -> Unit = {}): AgentResult {
        val key = ConversationKey(request.userId, request.chatId)
        val lock = retainLock(key)

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
            releaseLock(key)
        }
    }

    suspend fun handleScheduled(request: AgentRequest): AgentResult =
        handleQueued(request)

    suspend fun handleQueued(
        request: AgentRequest,
        onToolStarting: (activity: ToolActivity) -> Unit = {}
    ): AgentResult {
        val key = ConversationKey(request.userId, request.chatId)
        val lock = retainLock(key)

        try {
            return lock.withLock { runAgent(request, onToolStarting) }
        } finally {
            releaseLock(key)
        }
    }

    // a turn persists its own history under this lock, so an outside clear (the `/clear` command) has to
    // take the same lock or a turn already in flight would append itself back into the wiped history.
    // the lock is keyed by what it guards, one conversation, so the same person writing in two chats is
    // served in both instead of being told the bot is busy.
    // `clearConversation` runs inside a turn and must keep using the repository directly.
    suspend fun clearConversation(userId: Long, chatId: Long) {
        val key = ConversationKey(userId, chatId)
        val lock = retainLock(key)

        try {
            lock.withLock { conversation.clear(userId, chatId) }
        } finally {
            releaseLock(key)
        }
    }

    private suspend fun runAgent(request: AgentRequest, onToolStarting: (activity: ToolActivity) -> Unit = {}): AgentResult {
        tokenBudget.exhaustedFor()?.let { untilReset ->
            log.warn {
                "daily token budget spent: turn skipped for chat=${request.chatId} user=${request.userId}, " +
                        "resetsIn=$untilReset"
            }

            return AgentResult(
                outputs = emptyList(),
                comment = Messages.of(request.language).tokenBudgetExhaustedReply(untilReset)
            )
        }

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
            request.messageContext?.copy(
                previousExchangeAt = conversation.lastInteractionAt(request.userId, request.chatId)
            )

        val currentTurn =
            currentTurnPrompt(request.prompt, messageContext, userMemory, chatMemory, recentChatFor(context))
        val outbox = BotOutbox()

        val preparation =
            agentFactory.prepare(
                context = context,
                outbox = outbox,
                currentTurn = currentTurn,
                stickerCatalog = stickers?.indexBlockFor(request.chatId)
            )

        val conversationPlan =
            conversationPlanForPrompt(request.userId, request.chatId, preparation.tokenBudget.conversationTokens)
        val plannedInputTokens = preparation.tokenBudget.fixedPromptTokens + conversationPlan.estimatedTokens

        log.info {
            "prompt history loaded: user=${request.userId} chat=${request.chatId} " +
                    "storedInteractions=${conversationPlan.stats.storedInteractions} storedMessages=${conversationPlan.stats.storedMessages} " +
                    "storedChars=${conversationPlan.stats.storedChars} unsummarized=${conversationPlan.stats.unsummarizedInteractions} " +
                    "includedInteractions=${conversationPlan.includedInteractions} turns=${conversationPlan.prompt.turns.size} " +
                    "summaryChars=${conversationPlan.prompt.summary?.length ?: 0} exactToolInteractions=${conversationPlan.exactToolInteractions} " +
                    "userMemory=${userMemory.size} chatMemory=${chatMemory.size} " +
                    "promptChars=${request.prompt.length} historyChars=${request.conversationEntry.length} " +
                    "attachedFile=${request.attachedFile != null}"
        }

        log.info {
            "prompt context plan: user=${request.userId} chat=${request.chatId} " +
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
                    userId = request.userId,
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
                budgetStopReply(request, e)?.let { return AgentResult(outputs = emptyList(), comment = it) }
                return AgentResult(outputs = emptyList(), comment = replyForAgentFailure(request, e), failed = true)
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

        val turns =
            buildTurns(
                userEntry = request.conversationEntry,
                toolEvents = toolEvents,
                assistantText = assistantText
            )

        log.info {
            "agent reply: chat=${request.chatId} user=${request.userId} " +
                    "outputs=[${outputsLogSummary(outputs)}] " +
                    "text=[${assistantText?.collapseWhitespaceAndCap(LOG_REPLY_MAX_CHARS).orEmpty()}]"
        }

        if (turns.isNotEmpty()) {
            conversation.appendInteraction(request.userId, request.chatId, turns)
        }

        val pruned =
            conversation.pruneCompacted(
                userId = request.userId,
                chatId = request.chatId,
                maxStoredInteractions = conversationConfig.maxStoredInteractions,
                rawRetentionCutoff = Instant.now().minus(conversationConfig.retentionDays.toLong(), ChronoUnit.DAYS)
            )

        if (pruned > 0) {
            log.info { "history raw retention pruned: user=${request.userId} interactions=$pruned" }
        }

        return AgentResult(outputs, comment, outbox.redirectToPrivate)
    }

    // what the group was saying just before this turn. in a group the bot only ever sees the messages
    // addressed to it, so without this a question like "and what do you think?" arrives with no subject.
    // the triggering message is left out — the model is already being shown it as the request itself.
    private suspend fun recentChatFor(context: RequestContext): String? {
        val repository = groupLog?.takeIf { groupLogConfig.recentChatEnabled && !context.chatIsPrivate } ?: return null

        val entries =
            try {
                repository.recent(
                    chatId = context.chatId,
                    // over-fetch: dropping this user's own exchanges below must not thin the slice out.
                    limit = groupLogConfig.recentMessages * RECENT_CHAT_OVERFETCH,
                    since = Instant.now().minus(groupLogConfig.recentMinutes.toLong(), ChronoUnit.MINUTES),
                    excludeMessageId = context.messageId
                )
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn(e) { "failed to load the recent chat slice for chat=${context.chatId}" }
                return null
            }

        val recent =
            entries
                .withoutExchangesWith(context.userId)
                .takeLast(groupLogConfig.recentMessages)

        return renderGroupLog(recent, ZoneId.systemDefault(), RECENT_CHAT_LINE_CHARS, RECENT_CHAT_MAX_CHARS)
            .text
            .takeIf { it.isNotBlank() }
    }

    // at most one recap per turn: it is an extra LLM round trip in front of the user's reply. whatever
    // is still over budget stays out of this prompt and gets its own recap on a later turn.
    private suspend fun conversationPlanForPrompt(userId: Long, chatId: Long, tokenBudget: Int): ConversationPlan {
        val snapshot = conversation.load(userId, chatId)
        val plan = planFor(snapshot, tokenBudget)

        if (plan.compactablePrefix.isEmpty()) return plan

        val compacted =
            try {
                conversationCompactor.compact(snapshot.summary, plan.compactablePrefix)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn {
                    "history recap failed for user=$userId chat=$chatId: " +
                            e.message?.collapseWhitespaceAndCap(PROVIDER_ERROR_LOG_MAX_CHARS).orEmpty()
                }
                return plan
            } ?: return plan

        val stored =
            conversation.storeSummary(
                userId = userId,
                chatId = chatId,
                expectedThroughMessageId = snapshot.summarizedThroughMessageId,
                throughMessageId = compacted.throughMessageId,
                content = compacted.summary
            )

        if (!stored) {
            log.warn {
                "history recap checkpoint changed before store for user=$userId chat=$chatId; keeping the raw history"
            }
            return plan
        }

        log.info {
            "history recap stored: user=$userId chat=$chatId interactions=${compacted.interactionCount} " +
                    "throughMessage=${compacted.throughMessageId} chars=${compacted.summary.length}"
        }

        return planFor(conversation.load(userId, chatId), tokenBudget)
    }

    private fun planFor(snapshot: ConversationSnapshot, tokenBudget: Int): ConversationPlan =
        planConversation(
            snapshot = snapshot,
            tokenBudget = tokenBudget,
            maxRecentInteractions = conversationConfig.maxRecentInteractions
        )

    private suspend fun runAgentWithConversation(
        userId: Long,
        currentTurn: String,
        conversation: PromptConversation,
        preparation: AgentPromptPreparation,
        outbox: BotOutbox,
        toolEvents: MutableList<ToolEvent>,
        tokenUsages: MutableList<TokenUsage>,
        onToolStarting: (activity: ToolActivity) -> Unit
    ): String {
        suspend fun run(prompt: PromptConversation): String =
            agentFactory
                .build(
                    userId = userId,
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

            log.warn { "context limit exceeded for user=$userId; retrying once with recap only" }
            run(emergencyConversation)
        }
    }

    // the budget ran out with the turn already in flight. that is not a failure to retry — the turn is over
    // until the budget resets — so it gets the same "come back later" reply as a turn that never started.
    private fun budgetStopReply(request: AgentRequest, e: Throwable): String? =
        e.tokenBudgetStop()
            ?.let { stop ->
                log.warn {
                    "daily token budget spent mid-turn for chat=${request.chatId} user=${request.userId}, " +
                            "resetsIn=${stop.untilReset}"
                }

                Messages.of(request.language).tokenBudgetExhaustedReply(stop.untilReset)
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

    private fun retainLock(key: ConversationKey): Mutex =
        synchronized(conversationLocks) {
            conversationLocks.getOrPut(key) { ConversationLock() }.also { it.refCount++ }.mutex
        }

    private fun releaseLock(key: ConversationKey) {
        synchronized(conversationLocks) {
            val entry = conversationLocks[key] ?: return
            if (--entry.refCount <= 0) conversationLocks.remove(key)
        }
    }

    private data class ConversationKey(val userId: Long, val chatId: Long)

    private class ConversationLock(val mutex: Mutex = Mutex(), var refCount: Int = 0)
}

internal fun currentTurnPrompt(
    userInput: String,
    messageContext: MessageContext?,
    userMemory: List<MemoryEntry>,
    chatMemory: List<MemoryEntry>,
    recentChat: String? = null
): String =
    buildList {
        messageContext?.toPromptBlock()?.let(::add)
        memoryBlock("user_memory", userMemory)?.let(::add)
        memoryBlock("group_memory", chatMemory)?.let(::add)
        recentChat?.takeIf { it.isNotBlank() }?.let { add(xmlBlock("recent_chat", it)) }
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

internal fun buildTurns(userEntry: String, toolEvents: List<ToolEvent>, assistantText: String?): List<ChatTurn> =
    buildList {
        add(ChatTurn(role = ChatRole.USER, content = userEntry))

        for (event in toolEvents.forHistory()) {

            add(
                ChatTurn(
                    role = ChatRole.TOOL_CALL,
                    content = toolCallArgsForStorage(event.args),
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

        val args = toolCallArgsForStorage(event.args)
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
