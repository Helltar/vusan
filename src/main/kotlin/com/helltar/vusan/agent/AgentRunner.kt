package com.helltar.vusan.agent

import com.helltar.vusan.agent.history.*
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.MemoryScope
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.isEffectivelyBlank
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.request.RepliedPhoto
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.message.MessageTools
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentRequest(
    val chatId: Long,
    val userId: Long,
    val messageId: Long,
    val replyToMessageId: Long? = null,
    val prompt: String,
    val historyEntry: String,
    val messageContext: MessageContext? = null,
    val chatIsPrivate: Boolean = false,
    val repliedPhoto: RepliedPhoto? = null,
    val language: Language = Language.DEFAULT
)

data class AgentResult(
    val outputs: List<OutboxItem>,
    val comment: String?,
    val commentToPrivate: Boolean = false,
    val historyTurns: List<ChatTurn> = emptyList()
)

class AgentRunner(
    private val agentFactory: AgentFactory,
    private val history: ChatHistoryRepository,
    private val memory: MemoryRepository
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val userLocks = HashMap<Long, UserLock>()

    suspend fun handle(request: AgentRequest): AgentResult {
        val lock = retainLock(request.userId)

        try {
            if (!lock.tryLock()) {
                return AgentResult(outputs = emptyList(), comment = Messages.of(request.language).busyReply)
            }

            try {
                return runAgent(request)
            } finally {
                lock.unlock()
            }
        } finally {
            releaseLock(request.userId)
        }
    }

    suspend fun handleScheduled(request: AgentRequest): AgentResult {
        val lock = retainLock(request.userId)

        try {
            return lock.withLock { runAgent(request) }
        } finally {
            releaseLock(request.userId)
        }
    }

    private suspend fun runAgent(request: AgentRequest): AgentResult {
        val context =
            RequestContext(
                chatId = request.chatId,
                userId = request.userId,
                messageId = request.messageId,
                replyToMessageId = request.replyToMessageId,
                repliedPhoto = request.repliedPhoto,
                senderUsername = request.messageContext?.userUsername,
                senderDisplayName = request.messageContext?.userDisplayName,
                chatIsPrivate = request.messageContext?.isPrivate ?: request.chatIsPrivate,
                language = request.language
            )

        val outbox = BotOutbox()
        val promptHistory = summarizeForPrompt(history.load(request.userId))

        val userMemory = memory.load(MemoryScope.USER, request.userId)
        val chatMemory = if (context.chatIsPrivate) emptyList() else memory.load(MemoryScope.CHAT, request.chatId)

        log.info {
            "prompt history loaded: user=${request.userId} chat=${request.chatId} " +
                    "turns=${promptHistory.turns.size} summaryChars=${promptHistory.summary?.length ?: 0} " +
                    "userMemory=${userMemory.size} chatMemory=${chatMemory.size} " +
                    "promptChars=${request.prompt.length} historyChars=${request.historyEntry.length} " +
                    "repliedPhoto=${request.repliedPhoto != null}"
        }

        val toolEvents = mutableListOf<ToolEvent>()
        val tokenUsages = mutableListOf<TokenUsage>()

        val agent =
            agentFactory.build(
                userId = request.userId,
                history = promptHistory,
                context = context,
                outbox = outbox,
                toolEvents = toolEvents::add,
                tokenUsage = tokenUsages::add,
                messageContext = request.messageContext,
                userMemory = userMemory,
                chatMemory = chatMemory
            )

        val answer =
            try {
                agent.run(request.prompt)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.error(e) { "agent.run failed for chat=${request.chatId} user=${request.userId}" }
                return AgentResult(outputs = emptyList(), comment = Messages.of(request.language).fallbackErrorReply)
            }

        log.info {
            "token usage: chat=${request.chatId} user=${request.userId} ${tokenUsageLogSummary(tokenUsages)}"
        }

        val outputs = outbox.pending
        val comment = extractFinalComment(answer, outputs)

        if (outputs.isEmpty() && comment.isNullOrBlank()) {
            log.warn { "agent produced no output for chat=${request.chatId} user=${request.userId}" }
            return AgentResult(outputs = emptyList(), comment = Messages.of(request.language).fallbackErrorReply)
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

        return AgentResult(outputs, comment, outbox.redirectToPrivate, historyTurns)
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

private const val TOOL_OUTPUT_MAX_CHARS = 4_000
private const val LOG_REPLY_MAX_CHARS = 300

private fun tokenUsageLogSummary(usages: List<TokenUsage>): String {

    fun List<Int?>.sumOrNa(): String =
        filterNotNull().let { if (it.isEmpty()) "n/a" else it.sum().toString() }

    val promptTokens = usages.lastOrNull()?.inputTokens

    return "calls=${usages.size} promptTokens=${promptTokens ?: "n/a"} " +
            "billedInput=${usages.map { it.inputTokens }.sumOrNa()} " +
            "billedOutput=${usages.map { it.outputTokens }.sumOrNa()} " +
            "runTotal=${usages.map { it.totalTokens }.sumOrNa()}"
}

private fun outputsLogSummary(outputs: List<OutboxItem>): String =
    outputs.joinToString(", ") { item ->
        when (val output = item.output) {
            is BotOutput.Reaction -> "reaction ${output.emoji}"
            is BotOutput.PhotoGroup -> "photoGroup(${output.photos.size})"
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
                        it.output is BotOutput.Reaction
            }
        }

private fun assistantTextForHistory(outputs: List<OutboxItem>, comment: String?): String? {
    val parts =
        buildList {
            addAll(outputs.mapNotNull { it.output as? BotOutput.Text }.map { it.text })
            comment?.let(::add)
        }

    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

// Tools whose payload is fully duplicated by the assistant text row. Skipping their
// TOOL_CALL/TOOL_RESULT pair avoids storing (and replaying) the same content twice.
// Koog registers each tool under its function name (no tool here sets @Tool(customName)),
// so a function reference stays in sync with the registered name across renames.
private val TEXT_DUPLICATING_TOOLS = setOf(MessageTools::sendMessage.name)

private fun buildHistoryTurns(userEntry: String, toolEvents: List<ToolEvent>, assistantText: String?): List<ChatTurn> =
    buildList {
        add(ChatTurn(role = ChatRole.USER, content = userEntry))

        for (event in toolEvents) {
            if (event.toolName in TEXT_DUPLICATING_TOOLS) continue

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
