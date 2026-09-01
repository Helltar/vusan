package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.InlineChoiceSelection
import com.helltar.vusan.telegram.callback.inlineChoiceAgentInput
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.telegram.inbound.attachedFileContextBlock
import com.helltar.vusan.telegram.inbound.canLoadChatDescription
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.formatAgentInput
import com.helltar.vusan.telegram.inbound.formatConversationInput
import com.helltar.vusan.telegram.inbound.isReplyToOtherUser
import com.helltar.vusan.telegram.inbound.language
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.quotedFragmentOrNull
import com.helltar.vusan.telegram.inbound.repliedAttachedFileOrNull
import com.helltar.vusan.telegram.inbound.replyAuthorIdOrNull
import com.helltar.vusan.telegram.inbound.replySummaryOrNull
import com.helltar.vusan.telegram.inbound.replyToMessageIdOrNull
import com.helltar.vusan.telegram.inbound.senderIdOrNull
import com.helltar.vusan.telegram.inbound.toMessageContext
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * One agent turn, from a normalized Telegram input to the answer in the chat. `TelegramBotRunner` decides
 * what an update means and produces the prompt; everything after that — the reply context, the
 * `AgentRequest`, the progress indicator, the parked choice attachment, the delivery and its fallback —
 * happens here, the same way whether the turn was started by a message or by a button selection.
 */
internal class AgentTurns(
    private val client: TelegramClient,
    private val agent: AgentRunner,
    private val delivery: TelegramDelivery,
    private val inlineChoices: InlineChoiceHandler,
    private val chatProfiles: ChatProfiles,
    private val voiceTranscriber: VoiceTranscriber?
) {

    private companion object {
        const val LOG_PROMPT_MAX_CHARS = 300

        val log = KotlinLogging.logger {}
    }

    suspend fun dispatchToAgent(
        message: Message,
        prompt: String,
        botProfile: BotProfile,
        inputKind: String,
        loadRepliedAttachment: Boolean = true,
        attachedFile: AttachedFile? = null
    ) {
        // every reply describes what it answers, the bot's own messages included: the history that would
        // otherwise carry them belongs to one person and one chat, so in a group the message is missing
        // from the replier's history whenever it was written for somebody else.
        val replySummary = message.replySummaryOrNull(client, voiceTranscriber, botProfile.userId)
        val quotedFragment = message.quotedFragmentOrNull()

        // the file travels with it for the same reason, and because no history carries bytes: without this
        // "edit this" against a picture the bot itself drew has nothing to work on.
        val effectiveAttachedFile =
            attachedFile ?: if (loadRepliedAttachment) message.repliedAttachedFileOrNull(client) else null

        val baseAgentInput = formatAgentInput(prompt, replySummary, quotedFragment)

        handleAgentMessage(
            message = message,
            agentInput =
                effectiveAttachedFile?.let { "${attachedFileContextBlock(it)}\n\n$baseAgentInput" } ?: baseAgentInput,
            conversationInput = formatConversationInput(prompt, replySummary, quotedFragment),
            attachedFile = effectiveAttachedFile,
            // a reaction may only land on somebody else's message, so this stays narrower than the context above.
            replyToMessageId =
                message.replyToMessageIdOrNull()
                    ?.takeIf { isReplyToOtherUser(message.replyAuthorIdOrNull(), botProfile.userId) },
            inputKind = inputKind
        )
    }

    private suspend fun handleAgentMessage(
        message: Message,
        agentInput: String,
        conversationInput: String,
        attachedFile: AttachedFile?,
        replyToMessageId: Long?,
        inputKind: String
    ) {
        val chatId = message.chatIdLong

        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping $inputKind message without sender user (chat=$chatId)" }
                return
            }

        val language = message.language
        val request =
            AgentRequest(
                chatId = chatId,
                userId = userId,
                messageId = message.messageIdLong,
                replyToMessageId = replyToMessageId,
                prompt = agentInput,
                conversationEntry = conversationInput,
                messageContext = message.toMessageContext(chatProfile(message)),
                attachedFile = attachedFile,
                language = language
            )

        runAgentTurn(
            request = request,
            inputKind = inputKind,
            waitForTurn = false,
            deliver = { result -> delivery.send(message, result) },
            sendFallback = { delivery.sendReply(message, Messages.of(language).fallbackErrorReply) }
        )
    }

    suspend fun dispatchSelection(
        message: Message,
        user: User,
        selection: InlineChoiceSelection,
        messages: Messages
    ) {
        val input = inlineChoiceAgentInput(selection)
        val language = Language.fromCode(user.languageCode)
        val attachedFile = inlineChoices.parkedAttachment(message.chatIdLong, user.id)

        // the selection continues the exchange the user started, so the turn runs as if it came from that
        // message: it is what a reaction lands on, and what a task scheduled here is anchored to later.
        val request =
            AgentRequest(
                chatId = message.chatIdLong,
                userId = user.id,
                messageId = selection.originMessageId ?: 0L,
                prompt = attachedFile?.let { "${attachedFileContextBlock(it)}\n\n$input" } ?: input,
                conversationEntry = input,
                messageContext = message.toMessageContext(user, chatProfile(message)),
                attachedFile = attachedFile,
                language = language
            )

        runAgentTurn(
            request = request,
            inputKind = "inline choice",
            waitForTurn = true,
            deliver = { result ->
                delivery.sendCallback(
                    result = result,
                    message = message,
                    originMessageId = selection.originMessageId,
                    userId = user.id,
                    messages = messages
                )
            },
            sendFallback = { delivery.sendReply(message, messages.fallbackErrorReply, selection.originMessageId) }
        )
    }

    private suspend fun runAgentTurn(
        request: AgentRequest,
        inputKind: String,
        waitForTurn: Boolean,
        deliver: suspend (AgentResult) -> Unit,
        sendFallback: suspend () -> Unit
    ) {
        log.info {
            buildString {
                append("incoming $inputKind: chat=${request.chatId} user=${request.userId} msg=${request.messageId}")
                request.messageContext?.userUsername?.let { append(" username=[$it]") }
                request.messageContext?.userDisplayName?.let { append(" name=[$it]") }
                request.replyToMessageId?.let { append(" replyTo=$it") }
                request.attachedFile?.let { append(" attachedFile=[${it.name}]") }
                append(" text=[${request.prompt.collapseWhitespaceAndCap(LOG_PROMPT_MAX_CHARS).orEmpty()}]")
            }
        }

        try {
            // the agent gets the setter so the indicator follows the tool it is running; delivery then
            // shows its own per-item action.
            val result =
                client.withLiveProgress(request) { setActivity ->
                    if (waitForTurn)
                        agent.handleQueued(request, setActivity)
                    else
                        agent.handle(request, setActivity)
                }

            // a question with buttons ends the turn without answering, so whatever it was asked about has
            // to outlive it; any other turn clears the slot instead of leaving a stale file behind.
            inlineChoices.parkAttachment(
                chatId = request.chatId,
                userId = request.userId,
                file = request.attachedFile?.takeIf { result.outputs.any { it.output is BotOutput.InlineChoice } }
            )

            // the progress draft has to become the reply, so it is handed the text just before the send.
            client.handOffProgressDraft(request, result)
            deliver(result)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()

            log.error(error) {
                "telegram $inputKind handling failed for chat=${request.chatId} user=${request.userId}"
            }

            runCatching { sendFallback() }
                .onFailure { replyError ->
                    replyError.rethrowIfCancellation()
                    log.warn(replyError) {
                        "failed to send fallback error reply for chat=${request.chatId} user=${request.userId}"
                    }
                }
        }
    }

    // only a group-flavored chat has a description or restrictions to read; anywhere else the lookup
    // would spend two API calls to learn nothing.
    private suspend fun chatProfile(message: Message): ChatProfile =
        if (message.canLoadChatDescription) chatProfiles.of(message.chatIdLong) else ChatProfile.NONE
}
