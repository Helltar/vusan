package com.helltar.vusan.agent

import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.agent.history.ChatRole
import com.helltar.vusan.agent.history.ChatTurn
import com.helltar.vusan.agent.history.summarizeForPrompt
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.RepliedPhoto
import com.helltar.vusan.outbox.RequestContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class AgentRequest(
    val chatId: Long,
    val userId: Long,
    val messageId: Long,
    val replyToMessageId: Long? = null,
    val prompt: String,
    val historyEntry: String,
    val messageContext: MessageContext? = null,
    val repliedPhoto: RepliedPhoto? = null
)

data class AgentResult(
    val outputs: List<BotOutput>,
    val comment: String?,
    val commentToPrivate: Boolean = false,
    val historyTurns: List<ChatTurn> = emptyList()
)

class AgentRunner(private val agentFactory: AgentFactory, private val history: ChatHistoryRepository) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val userLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun handle(request: AgentRequest): AgentResult {
        val lock = acquireLock(request.userId)

        if (!lock.tryLock()) {
            return AgentResult(outputs = emptyList(), comment = Messages.busyReply)
        }

        try {
            return runAgent(request)
        } finally {
            lock.unlock()
        }
    }

    suspend fun handleScheduled(request: AgentRequest): AgentResult {
        val lock = acquireLock(request.userId)
        return lock.withLock { runAgent(request) }
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
                chatIsPrivate = request.messageContext?.chatType == "private",
            )

        val outbox = BotOutbox()
        val promptHistory = summarizeForPrompt(history.load(request.userId))

        log.info {
            "prompt history loaded: user=${request.userId} chat=${request.chatId} " +
                    "turns=${promptHistory.turns.size} summaryChars=${promptHistory.summary?.length ?: 0} " +
                    "promptChars=${request.prompt.length} historyChars=${request.historyEntry.length} " +
                    "repliedPhoto=${request.repliedPhoto != null}"
        }

        val toolEvents = mutableListOf<ToolEvent>()

        val agent =
            agentFactory.build(
                userId = request.userId,
                history = promptHistory,
                context = context,
                outbox = outbox,
                toolEvents = toolEvents::add,
                messageContext = request.messageContext
            )

        val answer =
            try {
                agent.run(request.prompt)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.error(e) { "agent.run failed for chat=${request.chatId} user=${request.userId}" }
                return AgentResult(outputs = emptyList(), comment = Messages.fallbackErrorReply)
            }

        val outputs = outbox.pending
        val comment = extractFinalComment(answer, outputs)
        val assistantText = assistantTextForHistory(outputs, comment)
        val historyTurns =
            buildHistoryTurns(
                userEntry = request.historyEntry,
                toolEvents = toolEvents,
                assistantText = assistantText
            )

        return AgentResult(outputs, comment, outbox.redirectToPrivate, historyTurns)
    }

    private fun acquireLock(userId: Long): Mutex =
        userLocks.computeIfAbsent(userId) { Mutex() }
}

private const val TOOL_ARGS_MAX_CHARS = 1_000
private const val TOOL_OUTPUT_MAX_CHARS = 4_000

private fun extractFinalComment(answer: String, outputs: List<BotOutput>): String? =
    answer.trim()
        .takeIf { it.isNotEmpty() }
        ?.takeUnless {
            outputs.any { it is BotOutput.Voice || it is BotOutput.VideoNote || it is BotOutput.Text || it is BotOutput.Reaction }
        }

private fun assistantTextForHistory(outputs: List<BotOutput>, comment: String?): String? {
    val parts =
        buildList {
            addAll(outputs.filterIsInstance<BotOutput.Text>().map { it.text })
            comment?.let(::add)
        }

    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

// Tools whose payload is fully duplicated by the assistant text row. Skipping their
// TOOL_CALL/TOOL_RESULT pair avoids storing (and replaying) the same content twice.
private val TEXT_DUPLICATING_TOOLS = setOf("sendMessage")

private fun buildHistoryTurns(
    userEntry: String,
    toolEvents: List<ToolEvent>,
    assistantText: String?
): List<ChatTurn> =
    buildList {
        add(ChatTurn(role = ChatRole.USER, content = userEntry))

        for (event in toolEvents) {
            if (event.toolName in TEXT_DUPLICATING_TOOLS) continue

            add(
                ChatTurn(
                    role = ChatRole.TOOL_CALL,
                    content = event.args.collapseWhitespaceAndCap(TOOL_ARGS_MAX_CHARS).orEmpty(),
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
