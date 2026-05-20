package com.helltar.vusan.agent

import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.agent.history.summarizeForPrompt
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.RepliedPhoto
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import java.util.Collections

data class AgentRequest(
    val chatId: Long,
    val userId: Long,
    val prompt: String,
    val historyEntry: String,
    val messageContext: MessageContext? = null,
    val repliedPhoto: RepliedPhoto? = null
)

data class AgentResult(
    val outputs: List<BotOutput>,
    val comment: String?,
    val commentToPrivate: Boolean = false,
    val assistantHistoryEntry: String? = null,
    val dispatchedMediaNote: String? = null
)

enum class ResetOutcome { Cleared, Busy }

class AgentRunner(private val agentFactory: AgentFactory, private val history: ChatHistoryRepository) {

    private companion object {
        const val USER_LOCK_CAPACITY = 128
        val log = KotlinLogging.logger {}
    }

    private val userLocks =
        Collections.synchronizedMap(
            object : LinkedHashMap<Long, Mutex>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<Long, Mutex>): Boolean = size > USER_LOCK_CAPACITY
            }
        )

    suspend fun handle(request: AgentRequest): AgentResult {
        val lock = acquireLock(request.userId)

        if (!lock.tryLock()) {
            return AgentResult(outputs = emptyList(), comment = Messages.busyReply)
        }

        try {
            val outbox =
                BotOutbox(
                    chatId = request.chatId,
                    userId = request.userId,
                    repliedPhoto = request.repliedPhoto
                )

            val promptHistory = summarizeForPrompt(history.load(request.userId))

            log.info {
                "prompt history loaded: user=${request.userId} chat=${request.chatId} " +
                        "turns=${promptHistory.turns.size} summaryChars=${promptHistory.summary?.length ?: 0} " +
                        "promptChars=${request.prompt.length} historyChars=${request.historyEntry.length} " +
                        "repliedPhoto=${request.repliedPhoto != null}"
            }

            val agent = agentFactory.build(request.userId, promptHistory, outbox, request.messageContext)

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
            val mediaNote = dispatchedMediaNote(outputs)
            val assistantText =
                assistantTextForHistory(outputs, comment)
                    ?: Messages.fallbackErrorReply.takeIf { mediaNote == null }

            return AgentResult(outputs, comment, outbox.redirectToPrivate, assistantText, mediaNote)
        } finally {
            lock.unlock()
        }
    }

    suspend fun reset(userId: Long): ResetOutcome {
        val lock = acquireLock(userId)

        if (!lock.tryLock()) {
            return ResetOutcome.Busy
        }

        try {
            history.clear(userId)
            return ResetOutcome.Cleared
        } finally {
            lock.unlock()
        }
    }

    private fun acquireLock(userId: Long): Mutex =
        userLocks.computeIfAbsent(userId) { Mutex() }
}

private fun extractFinalComment(answer: String, outputs: List<BotOutput>): String? =
    answer.trim()
        .takeIf { it.isNotEmpty() }
        ?.takeUnless {
            outputs.any { it is BotOutput.Voice || it is BotOutput.VideoNote || it is BotOutput.Text }
        }

// The assistant turn stores only what the model actually said. Media the bot dispatched is a
// tool-side action, not speech: it is recorded separately via `dispatchedMediaNote` and attached
// to the user turn so the model never sees it replayed as its own message and parrots it back.
private fun assistantTextForHistory(outputs: List<BotOutput>, comment: String?): String? {
    val parts =
        buildList {
            addAll(outputs.filterIsInstance<BotOutput.Text>().map { it.text })
            comment?.let(::add)
        }

    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

private fun dispatchedMediaNote(outputs: List<BotOutput>): String? =
    outputs.filter { it !is BotOutput.Text }
        .takeIf { it.isNotEmpty() }
        ?.let { "<sent>${summarizeNonText(it)}</sent>" }

private fun summarizeNonText(items: List<BotOutput>): String =
    if (items.size == 1)
        describe(items.single())
    else
        "${items.size} items: ${items.joinToString(", ", transform = ::describe)}"

private fun describe(item: BotOutput): String =
    when (item) {
        is BotOutput.Text -> "a text message"
        is BotOutput.Animation -> "a GIF"
        is BotOutput.Photo -> "a photo `${item.filename}`"
        is BotOutput.PhotoGroup -> "a photo album with ${item.photos.size} images"
        is BotOutput.Document -> "a file `${item.filename}`"
        is BotOutput.Audio -> "audio `${item.title}` by `${item.performer}`"
        is BotOutput.Voice -> "a voice message"
        is BotOutput.VideoNote -> "a video note"
        is BotOutput.Quiz -> "a quiz `${item.question}`"
        is BotOutput.Poll -> "a poll `${item.question}`"
    }
