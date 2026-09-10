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
import com.helltar.vusan.request.ChatProfile
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.InlineChoiceSelection
import com.helltar.vusan.telegram.callback.inlineChoiceAgentInput
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.telegram.inbound.attachedFileContextBlock
import com.helltar.vusan.telegram.inbound.canLoadChatDescription
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.formatAgentInput
import com.helltar.vusan.telegram.inbound.forumTopicIdOrNull
import com.helltar.vusan.telegram.inbound.formatConversationInput
import com.helltar.vusan.telegram.inbound.isReplyToOtherUser
import com.helltar.vusan.telegram.inbound.language
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.quotedFragmentOrNull
import com.helltar.vusan.telegram.inbound.repliedAttachedFileOrNull
import com.helltar.vusan.telegram.inbound.replyAuthorIdOrNull
import com.helltar.vusan.telegram.inbound.replySummaryOrNull
import com.helltar.vusan.telegram.inbound.replyToMessageIdOrNull
import com.helltar.vusan.telegram.inbound.toChatContext
import com.helltar.vusan.telegram.inbound.toSenderContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
        attachedFiles: List<AttachedFile> = emptyList()
    ) {
        // every reply describes what it answers, the bot's own messages included: the history that would
        // otherwise carry them belongs to one person and one chat, so in a group the message is missing
        // from the replier's history whenever it was written for somebody else.
        val replySummary = message.replySummaryOrNull(client, voiceTranscriber, botProfile.userId)
        val quotedFragment = message.quotedFragmentOrNull()

        // the file travels with it for the same reason, and because no history carries bytes: without this
        // "edit this" against a picture the bot itself drew has nothing to work on.
        val effectiveAttachedFiles =
            attachedFiles.ifEmpty {
                listOfNotNull(if (loadRepliedAttachment) message.repliedAttachedFileOrNull(client) else null)
            }

        val baseAgentInput = formatAgentInput(prompt, replySummary, quotedFragment)

        handleAgentMessage(
            message = message,
            agentInput =
                effectiveAttachedFiles.firstOrNull()
                    ?.let { "${attachedFileContextBlock(it)}\n\n$baseAgentInput" }
                    ?: baseAgentInput,
            conversationInput = formatConversationInput(prompt, replySummary, quotedFragment),
            attachedFiles = effectiveAttachedFiles,
            // a reaction may only land on somebody else's message, so this stays narrower than the context above.
            replyToMessageId =
                message.replyToMessageIdOrNull()
                    ?.takeIf { isReplyToOtherUser(message.replyAuthorIdOrNull(), botProfile.userId) }
                    ?.toString(),
            inputKind = inputKind
        )
    }

    private suspend fun handleAgentMessage(
        message: Message,
        agentInput: String,
        conversationInput: String,
        attachedFiles: List<AttachedFile>,
        replyToMessageId: String?,
        inputKind: String
    ) {
        val sender =
            message.from ?: run {
                log.warn { "skipping $inputKind message without sender user (chat=${message.chatIdLong})" }
                return
            }

        val request =
            AgentRequest(
                context =
                    RequestContext(
                        platform = Platform.TELEGRAM,
                        chat = message.toChatContext(chatProfile(message)),
                        sender = sender.toSenderContext(),
                        messageId = message.messageIdLong.toString(),
                        replyToMessageId = replyToMessageId,
                        attachedFiles = attachedFiles,
                        language = message.language
                    ),
                prompt = agentInput,
                conversationEntry = conversationInput
            )

        runAgentTurn(
            request = request,
            inputKind = inputKind,
            waitForTurn = false,
            deliver = { result -> delivery.send(message, result) },
            reply = { text -> delivery.sendReply(message, text) }
        )
    }

    suspend fun dispatchSelection(
        message: Message,
        user: User,
        selection: InlineChoiceSelection,
        messages: Messages
    ) {
        val input = inlineChoiceAgentInput(selection)
        val attachedFile = inlineChoices.parkedAttachment(message.chatIdLong, user.id)

        // the selection continues the exchange the user started, so the turn runs as if it came from that
        // message: it is what a reaction lands on, and what a task scheduled here is anchored to later.
        val request =
            AgentRequest(
                context =
                    RequestContext(
                        platform = Platform.TELEGRAM,
                        chat = message.toChatContext(chatProfile(message)),
                        sender = user.toSenderContext(),
                        messageId = selection.originMessageId?.toString(),
                        attachedFiles = listOfNotNull(attachedFile),
                        language = Language.fromCode(user.languageCode)
                    ),
                prompt = attachedFile?.let { "${attachedFileContextBlock(it)}\n\n$input" } ?: input,
                conversationEntry = input
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
            reply = { text -> delivery.sendReply(message, text, selection.originMessageId) }
        )
    }

    private suspend fun runAgentTurn(
        request: AgentRequest,
        inputKind: String,
        waitForTurn: Boolean,
        deliver: suspend (AgentResult) -> Unit,
        reply: suspend (text: String) -> Unit
    ) {
        val context = request.context

        log.info {
            buildString {
                append("incoming $inputKind: chat=${context.chat.id} user=${context.sender.id}")
                context.messageId?.let { append(" msg=$it") }
                context.sender.username?.let { append(" username=[$it]") }
                context.sender.displayName?.let { append(" name=[$it]") }
                context.replyToMessageId?.let { append(" replyTo=$it") }
                context.attachedFile?.let { append(" attachedFile=[${it.name}]") }
                append(" text=[${request.prompt.collapseWhitespaceAndCap(LOG_PROMPT_MAX_CHARS).orEmpty()}]")
            }
        }

        try {
            // the agent gets the setter so the indicator follows the tool it is running, and the status
            // so a tool can say what the turn is about to do while it still matters; delivery then shows
            // its own per-item action.
            val result =
                client.withLiveProgress(request) { setActivity, status ->
                    if (waitForTurn)
                        agent.handleQueued(request, setActivity, status)
                    else
                        agent.handle(request, setActivity, status)
                }

            // a question with buttons ends the turn without answering, so whatever it was asked about has
            // to outlive it; any other turn clears the slot instead of leaving a stale file behind.
            inlineChoices.parkAttachment(
                chatId = context.chatRef.telegramChatId,
                userId = context.user.telegramUserId,
                file = context.attachedFile?.takeIf { result.outputs.any { it.output is BotOutput.InlineChoice } }
            )

            deliver(result)
        } catch (error: Throwable) {
            // `/stop` cancels this job wherever it happens to be. The live status closes itself on the way
            // out, so all that is left is to say so in the chat — under NonCancellable, because the
            // coroutine is already leaving.
            if (error is CancellationException) {
                val messages = Messages.of(context.language)

                withContext(NonCancellable) {
                    runCatching {
                        reply(messages.turnStoppedNotice)
                    }.onFailure { stopError ->
                        log.warn(stopError) {
                            "failed to report a stopped turn for chat=${context.chat.id} user=${context.sender.id}"
                        }
                    }
                }

                throw error
            }

            log.error(error) {
                "telegram $inputKind handling failed for chat=${context.chat.id} user=${context.sender.id}"
            }

            runCatching { reply(Messages.of(context.language).fallbackErrorReply) }
                .onFailure { replyError ->
                    replyError.rethrowIfCancellation()
                    log.warn(replyError) {
                        "failed to send fallback error reply for chat=${context.chat.id} user=${context.sender.id}"
                    }
                }
        }
    }

    // only a group-flavored chat has a description or restrictions to read; anywhere else the lookup
    // would spend two API calls to learn nothing.
    private suspend fun chatProfile(message: Message): ChatProfile =
        if (message.canLoadChatDescription) chatProfiles.of(message.chatIdLong) else ChatProfile.NONE
}
