package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.request.AttachedFile
import dev.inmo.kslog.common.KSLog
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.chat.get.getChat
import dev.inmo.tgbotapi.extensions.api.send.withTypingAction
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.*
import dev.inmo.tgbotapi.types.chat.ExtendedPublicChat
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job

internal class TelegramBotRunner(
    private val bot: TelegramBot,
    private val delivery: TelegramDelivery,
    private val agent: AgentRunner,
    private val history: ChatHistoryRepository,
    private val allowedIds: Set<Long>,
    private val voiceTranscriber: VoiceTranscriber?
) {

    private companion object {
        const val MENTION_ONLY_PROMPT = "User mentioned the bot with no text. Respond naturally and briefly."

        const val MEDIA_ONLY_PROMPT =
            "User sent a file or image with no caption. " +
                    "If useful, describe it with `describeImage` or process it with `codeExecution`."

        const val LOG_PROMPT_MAX_CHARS = 300

        val log = KotlinLogging.logger {}
    }

    private data class BotProfile(
        val userId: Long,
        val username: String?
    )

    init {
        KSLog.default = KSLog { _, _, message: Any, _ -> log.debug { message } }
    }

    suspend fun start(): Job {
        val botProfile = bot.profile()

        log.info { "Bot started as ${botProfile.username ?: botProfile.userId}, allowed ids=${allowedIds.sorted()}" }

        if (allowedIds.isEmpty()) {
            log.warn {
                "ALLOWED_IDS is empty — bot will ignore every message. " +
                        "Set ALLOWED_IDS to user/chat ids that may use the bot."
            }
        }

        return bot.buildBehaviourWithLongPolling {
            onCommand("start", markerFactory = null) { handleStartCommand(it, botProfile) }
            onText(markerFactory = null) { handleTextUpdate(it, botProfile) }
            onSticker(markerFactory = null) { handleStickerUpdate(it, botProfile) }
            onVoice(markerFactory = null) { handleVoiceUpdate(it, botProfile) }
            onAudio(markerFactory = null) { handleAudioUpdate(it, botProfile) }
            onDocument(markerFactory = null) { handleDocumentUpdate(it, botProfile) }
            onPhoto(markerFactory = null) { handlePhotoUpdate(it, botProfile) }
        }
    }

    private val CommonMessage<*>.language: Language
        get() = Language.fromCode(senderLanguageCodeOrNull())

    private suspend fun handleStartCommand(message: CommonMessage<TextContent>, botProfile: BotProfile) {
        if (message.isAccepted(botProfile))
            sendReply(message, Messages.of(message.language).startReply)
    }

    private suspend fun handleTextUpdate(message: CommonMessage<TextContent>, botProfile: BotProfile) {
        if (isBotCommand(message.content)) return
        if (!message.isAccepted(botProfile)) return

        val userText =
            sanitizeUserText(message.content, botProfile.userId, botProfile.username)
                .ifBlank { MENTION_ONLY_PROMPT }

        dispatchToAgent(message, userText, botProfile, inputKind = "text")
    }

    private suspend fun handleVoiceUpdate(message: CommonMessage<VoiceContent>, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        handleTranscribedAudio(
            message = message,
            audioInput = message.content.media.toAudioInput(),
            caption = sanitizeUserText(message.content, botProfile.userId, botProfile.username),
            botProfile = botProfile,
            inputKind = "voice"
        )
    }

    private suspend fun handleAudioUpdate(message: CommonMessage<AudioContent>, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        handleTranscribedAudio(
            message = message,
            audioInput = message.content.media.toAudioInput(),
            caption = sanitizeUserText(message.content, botProfile.userId, botProfile.username),
            botProfile = botProfile,
            inputKind = "audio"
        )
    }

    private suspend fun handleTranscribedAudio(
        message: CommonMessage<*>,
        audioInput: AudioInput,
        caption: String,
        botProfile: BotProfile,
        inputKind: String
    ) {
        val transcriber = voiceTranscriber

        if (transcriber == null) {
            log.info {
                "$inputKind message ignored: STT not configured " +
                        "(chat=${message.chatIdLong} user=${message.senderIdOrNull()})"
            }

            return
        }

        val messages = Messages.of(message.language)

        val transcript =
            when (val result = transcriber.transcribe(bot, audioInput)) {
                is VoiceTranscriptionResult.Success -> result.text

                is VoiceTranscriptionResult.TooLong -> {
                    sendReply(message, messages.voiceTooLongReply(result.durationSeconds, result.maxSeconds))
                    return
                }

                is VoiceTranscriptionResult.Empty -> {
                    log.info { "$inputKind transcription empty (chat=${message.chatIdLong}): ${result.reason}" }
                    sendReply(message, messages.voiceEmptyReply)
                    return
                }

                is VoiceTranscriptionResult.Failed -> {
                    sendReply(message, messages.voiceTranscriptionFailedReply)
                    return
                }
            }

        val prompt = buildTranscribedPrompt(caption, transcript)

        dispatchToAgent(message, prompt, botProfile, inputKind = inputKind)
    }

    private fun buildTranscribedPrompt(caption: String, transcript: String): String {
        val wrapped = wrapAudioTranscript(transcript)
        val trimmedCaption = caption.trim()
        return if (trimmedCaption.isEmpty()) wrapped else "$trimmedCaption\n\n$wrapped"
    }

    private suspend fun handleStickerUpdate(message: CommonMessage<StickerContent>, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return
        val prompt = describeIncomingSticker(message.content.media)
        dispatchToAgent(message, prompt, botProfile, inputKind = "sticker", loadRepliedAttachment = false)
    }

    private suspend fun handleDocumentUpdate(message: CommonMessage<DocumentContent>, botProfile: BotProfile) =
        handleMediaUpdate(message, botProfile, inputKind = "document")

    private suspend fun handlePhotoUpdate(message: CommonMessage<PhotoContent>, botProfile: BotProfile) =
        handleMediaUpdate(message, botProfile, inputKind = "photo")

    private suspend fun handleMediaUpdate(message: CommonMessage<*>, botProfile: BotProfile, inputKind: String) {
        if (!message.isAccepted(botProfile)) return

        val caption =
            (message.content as? TextedContent)
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.username) }
                .orEmpty()
                .ifBlank { MEDIA_ONLY_PROMPT }

        dispatchToAgent(
            message,
            caption,
            botProfile,
            inputKind = inputKind,
            attachedFile = message.content.toAttachedFileOrNull(bot)
        )
    }

    private suspend fun CommonMessage<*>.usableReplySummary(botProfile: BotProfile): RepliedMessageSummary? =
        if (isReplyToOtherUser(replyAuthorIdOrNull(), botProfile.userId))
            replySummaryOrNull(bot, voiceTranscriber)
        else
            null

    private fun CommonMessage<*>.usableReplyToMessageId(botProfile: BotProfile): Long? =
        if (isReplyToOtherUser(replyAuthorIdOrNull(), botProfile.userId))
            replyToMessageIdOrNull()
        else
            null

    private fun CommonMessage<*>.isAccepted(botProfile: BotProfile): Boolean {
        if (!shouldHandle(this, botProfile.userId, botProfile.username))
            return false

        if (!isAllowed()) {
            logDenied()
            return false
        }

        return true
    }

    private fun CommonMessage<*>.logDenied() {
        log.warn {
            buildString {
                append("denied (not in allowlist): chat=$chatIdLong user=${senderIdOrNull()} type=${content.contentTypeName()}")
                senderUsernameOrNull()?.let { append(" username=[$it]") }
                senderDisplayNameOrNull()?.let { append(" name=[$it]") }
                textSnippetOrNull()?.collapseWhitespaceAndCap(LOG_PROMPT_MAX_CHARS)?.let { append(" text=[$it]") }
            }
        }
    }

    private fun CommonMessage<*>.isAllowed(): Boolean {
        if (allowedIds.isEmpty()) return false
        if (chatIdLong in allowedIds) return true
        val userId = senderIdOrNull() ?: return false
        return userId in allowedIds
    }

    private suspend fun dispatchToAgent(
        message: CommonMessage<*>,
        prompt: String,
        botProfile: BotProfile,
        inputKind: String,
        loadRepliedAttachment: Boolean = true,
        attachedFile: AttachedFile? = null
    ) {
        val replySummary = message.usableReplySummary(botProfile)
        val effectiveAttachedFile =
            attachedFile
                ?: if (loadRepliedAttachment) replySummary?.let { message.repliedAttachedFileOrNull(bot) } else null

        val baseAgentInput = replySummary?.let { formatAgentInput(prompt, it) } ?: prompt

        handleAgentMessage(
            message = message,
            agentInput =
                effectiveAttachedFile?.let { "${attachedFileContextBlock(it)}\n\n$baseAgentInput" } ?: baseAgentInput,
            historyInput = replySummary?.let { formatHistoryInput(prompt, it) } ?: prompt,
            attachedFile = effectiveAttachedFile,
            replyToMessageId = message.usableReplyToMessageId(botProfile),
            inputKind = inputKind
        )
    }

    private suspend fun handleAgentMessage(
        message: CommonMessage<*>,
        agentInput: String,
        historyInput: String,
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

        log.info {
            buildString {
                append("incoming $inputKind: chat=$chatId user=$userId msg=${message.messageIdLong}")
                message.senderUsernameOrNull()?.let { append(" username=[$it]") }
                message.senderDisplayNameOrNull()?.let { append(" name=[$it]") }
                replyToMessageId?.let { append(" replyTo=$it") }
                attachedFile?.let { append(" attachedFile=[${it.name}]") }
                append(" text=[${agentInput.collapseWhitespaceAndCap(LOG_PROMPT_MAX_CHARS).orEmpty()}]")
            }
        }

        bot.withTypingAction(chatId.toChatIdentifier()) {
            try {
                val result =
                    agent.handle(
                        AgentRequest(
                            chatId = chatId,
                            userId = userId,
                            messageId = message.messageIdLong,
                            replyToMessageId = replyToMessageId,
                            prompt = agentInput,
                            historyEntry = historyInput,
                            messageContext = message.toMessageContext(loadChatDescription(message)),
                            attachedFile = attachedFile,
                            language = message.language
                        )
                    )

                delivery.send(message, result)

                if (result.historyTurns.isNotEmpty()) {
                    history.appendTurns(userId, result.historyTurns)
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()

                log.error(error) { "telegram $inputKind handling failed for chat=$chatId user=$userId" }

                runCatching { sendReply(message, Messages.of(message.language).fallbackErrorReply) }
                    .onFailure { replyError ->
                        replyError.rethrowIfCancellation()
                        log.warn(replyError) { "failed to send fallback error reply for chat=$chatId user=$userId" }
                    }
            }
        }
    }

    private suspend fun sendReply(message: CommonMessage<*>, text: String) {
        TelegramOutputSender.sendText(
            bot = bot,
            chatId = message.chatIdLong.toChatIdentifier(),
            text = text,
            replyParameters = replyParameters(message.chatIdLong, message.messageIdLong)
        )
    }

    private suspend fun loadChatDescription(message: CommonMessage<*>): String? {
        if (!message.canLoadChatDescription) return null

        return runCatching { bot.getChat(message.chatIdLong.toChatIdentifier()) as? ExtendedPublicChat }
            .onFailure { error ->
                error.rethrowIfCancellation()
                log.debug(error) { "failed to fetch extended chat context for chat=${message.chatIdLong}" }
            }
            .getOrNull()?.description
    }

    private suspend fun TelegramBot.profile(): BotProfile {
        val me = getMe()
        return BotProfile(userId = me.id.chatId.long, username = me.username?.full)
    }
}
