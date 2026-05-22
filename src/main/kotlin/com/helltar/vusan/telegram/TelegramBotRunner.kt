package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.ResetOutcome
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.RepliedPhoto
import dev.inmo.kslog.common.KSLog
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.chat.get.getChat
import dev.inmo.tgbotapi.extensions.api.send.withTypingAction
import dev.inmo.tgbotapi.extensions.api.telegramBot
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onAudio
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onCommand
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onSticker
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onVoice
import dev.inmo.tgbotapi.types.chat.ExtendedPublicChat
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.AudioContent
import dev.inmo.tgbotapi.types.message.content.StickerContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.content.VoiceContent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job

internal class TelegramBotRunner(
    botToken: String,
    private val agent: AgentRunner,
    private val history: ChatHistoryRepository,
    private val allowedIds: Set<Long>,
    private val voiceTranscriber: VoiceTranscriber?
) {

    private companion object {
        const val MENTION_ONLY_PROMPT = "User mentioned the bot with no text. Respond naturally and briefly."
        val log = KotlinLogging.logger {}
    }

    private data class BotProfile(
        val userId: Long,
        val username: String?
    )

    private val bot = telegramBot(botToken)
    private val delivery = TelegramDelivery(bot)

    init {
        KSLog.default = KSLog { _, _, message: Any, _ -> log.debug { message } }
    }

    suspend fun start(): Job {
        val botProfile = bot.profile()

        log.info { "Bot started as ${botProfile.username ?: botProfile.userId}, allowed ids=${allowedIds.sorted()}" }

        if (allowedIds.isEmpty()) {
            log.warn { "ALLOWED_IDS is empty — bot will ignore every message. Set ALLOWED_IDS to user/chat ids that may use the bot." }
        }

        return bot.buildBehaviourWithLongPolling {
            onCommand("start", markerFactory = null) { handleStartCommand(it, botProfile) }
            onCommand("reset", markerFactory = null) { handleResetCommand(it, botProfile) }
            onText(markerFactory = null) { handleTextUpdate(it, botProfile) }
            onSticker(markerFactory = null) { handleStickerUpdate(it, botProfile) }
            onVoice(markerFactory = null) { handleVoiceUpdate(it, botProfile) }
            onAudio(markerFactory = null) { handleAudioUpdate(it, botProfile) }
        }
    }

    private suspend fun handleStartCommand(message: CommonMessage<TextContent>, botProfile: BotProfile) {
        if (message.isAccepted(botProfile))
            sendReply(message, Messages.startReply)
    }

    private suspend fun handleResetCommand(message: CommonMessage<TextContent>, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        val userId = message.senderIdOrNull() ?: return

        val reply =
            when (agent.reset(userId)) {
                ResetOutcome.Cleared -> Messages.resetReply
                ResetOutcome.Busy -> Messages.resetBusyReply
            }

        sendReply(message, reply)
    }

    private suspend fun handleTextUpdate(message: CommonMessage<TextContent>, botProfile: BotProfile) {
        if (isBotCommand(message.content)) return
        if (!message.isAccepted(botProfile)) return

        val userText =
            sanitizeUserText(message.content, botProfile.userId, botProfile.username)
                .ifBlank { MENTION_ONLY_PROMPT }

        val replySummary = message.usableReplySummary(botProfile)

        handleAgentMessage(
            message = message,
            agentInput = replySummary?.let { formatAgentInput(userText, it) } ?: userText,
            historyInput = replySummary?.let { formatHistoryInput(userText, it) } ?: userText,
            repliedPhoto = replySummary?.let { message.repliedPhotoOrNull(bot) },
            replyToMessageId = message.usableReplyToMessageId(botProfile),
            inputKind = "text"
        )
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
            log.info { "$inputKind message ignored: STT not configured (chat=${message.chatIdLong} user=${message.senderIdOrNull()})" }
            return
        }

        val transcript =
            when (val result = transcriber.transcribe(bot, audioInput)) {
                is VoiceTranscriptionResult.Success -> result.text
                is VoiceTranscriptionResult.TooLong -> {
                    sendReply(message, Messages.voiceTooLongReply(result.durationSeconds, result.maxSeconds))
                    return
                }
                is VoiceTranscriptionResult.Empty -> {
                    log.info { "$inputKind transcription empty (chat=${message.chatIdLong}): ${result.reason}" }
                    sendReply(message, Messages.voiceEmptyReply)
                    return
                }
                is VoiceTranscriptionResult.Failed -> {
                    sendReply(message, Messages.voiceTranscriptionFailedReply)
                    return
                }
            }

        val prompt = buildTranscribedPrompt(caption, transcript)
        val replySummary = message.usableReplySummary(botProfile)

        handleAgentMessage(
            message = message,
            agentInput = replySummary?.let { formatAgentInput(prompt, it) } ?: prompt,
            historyInput = replySummary?.let { formatHistoryInput(prompt, it) } ?: prompt,
            repliedPhoto = replySummary?.let { message.repliedPhotoOrNull(bot) },
            replyToMessageId = message.usableReplyToMessageId(botProfile),
            inputKind = inputKind
        )
    }

    private fun buildTranscribedPrompt(caption: String, transcript: String): String {
        val wrapped = wrapAudioTranscript(transcript)
        val trimmedCaption = caption.trim()

        return if (trimmedCaption.isEmpty()) wrapped else "$trimmedCaption\n\n$wrapped"
    }

    private suspend fun handleStickerUpdate(message: CommonMessage<StickerContent>, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        val prompt = describeIncomingSticker(message.content.media)
        val replySummary = message.usableReplySummary(botProfile)

        handleAgentMessage(
            message = message,
            agentInput = replySummary?.let { formatAgentInput(prompt, it) } ?: prompt,
            historyInput = replySummary?.let { formatHistoryInput(prompt, it) } ?: prompt,
            repliedPhoto = null,
            replyToMessageId = message.usableReplyToMessageId(botProfile),
            inputKind = "sticker"
        )
    }

    private suspend fun CommonMessage<*>.usableReplySummary(botProfile: BotProfile): RepliedMessageSummary? =
        if (isReplyToOtherUser(replyAuthorIdOrNull(), botProfile.userId))
            replySummaryOrNull(bot, voiceTranscriber)
        else null

    private fun CommonMessage<*>.usableReplyToMessageId(botProfile: BotProfile): Long? =
        if (isReplyToOtherUser(replyAuthorIdOrNull(), botProfile.userId)) replyToMessageIdOrNull() else null

    private fun CommonMessage<*>.isAccepted(botProfile: BotProfile): Boolean {
        if (!shouldHandle(this, botProfile.userId, botProfile.username)) return false

        if (!isAllowed()) {
            log.warn { "denied: chat=$chatIdLong user=${senderIdOrNull()} not in allowlist" }
            return false
        }

        return true
    }

    private fun CommonMessage<*>.isAllowed(): Boolean {
        if (allowedIds.isEmpty()) return false
        if (chatIdLong in allowedIds) return true
        val userId = senderIdOrNull() ?: return false
        return userId in allowedIds
    }

    private suspend fun handleAgentMessage(
        message: CommonMessage<*>,
        agentInput: String,
        historyInput: String,
        repliedPhoto: RepliedPhoto?,
        replyToMessageId: Long?,
        inputKind: String
    ) {
        val chatId = message.chatIdLong

        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping $inputKind message without sender user (chat=$chatId)" }
                return
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
                            repliedPhoto = repliedPhoto
                        )
                    )

                delivery.send(message, result)

                if (result.historyTurns.isNotEmpty()) {
                    history.appendTurns(userId, result.historyTurns)
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()

                log.error(error) { "telegram $inputKind handling failed for chat=$chatId user=$userId" }

                runCatching { sendReply(message, Messages.fallbackErrorReply) }
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
