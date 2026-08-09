package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.InlineChoiceSelection
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.callback.inlineChoiceAgentInput
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.delivery.TelegramOutputSender
import com.helltar.vusan.telegram.delivery.answerCallbackQuery
import com.helltar.vusan.telegram.delivery.chatActionFor
import com.helltar.vusan.telegram.delivery.replyParameters
import com.helltar.vusan.telegram.inbound.AudioInput
import com.helltar.vusan.telegram.inbound.BotCommand
import com.helltar.vusan.telegram.inbound.MessageText
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.telegram.inbound.VoiceTranscriptionResult
import com.helltar.vusan.telegram.inbound.attachedFileContextBlock
import com.helltar.vusan.telegram.inbound.canLoadChatDescription
import com.helltar.vusan.telegram.inbound.captionedPartOrNull
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.describeIncomingSticker
import com.helltar.vusan.telegram.inbound.formatAgentInput
import com.helltar.vusan.telegram.inbound.formatConversationInput
import com.helltar.vusan.telegram.inbound.isPrivateChat
import com.helltar.vusan.telegram.inbound.isReplyToOtherUser
import com.helltar.vusan.telegram.inbound.leadingBotCommandOrNull
import com.helltar.vusan.telegram.inbound.logDenied
import com.helltar.vusan.telegram.inbound.logIncoming
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.messageTextOrNull
import com.helltar.vusan.telegram.inbound.normalizeUsername
import com.helltar.vusan.telegram.inbound.repliedAttachedFileOrNull
import com.helltar.vusan.telegram.inbound.replyAuthorIdOrNull
import com.helltar.vusan.telegram.inbound.replySummaryOrNull
import com.helltar.vusan.telegram.inbound.replyToMessageIdOrNull
import com.helltar.vusan.telegram.inbound.sanitizeUserText
import com.helltar.vusan.telegram.inbound.senderIdOrNull
import com.helltar.vusan.telegram.inbound.senderLanguageCodeOrNull
import com.helltar.vusan.telegram.inbound.shouldHandle
import com.helltar.vusan.telegram.inbound.textSnippetOrNull
import com.helltar.vusan.telegram.inbound.toAttachedFileOrNull
import com.helltar.vusan.telegram.inbound.toAudioInput
import com.helltar.vusan.telegram.inbound.toGroupLogEntry
import com.helltar.vusan.telegram.inbound.toMessageContext
import com.helltar.vusan.telegram.inbound.toRichMarkdown
import com.helltar.vusan.telegram.inbound.wrapAudioTranscript
import com.helltar.vusan.tools.sticker.StickerCatalog
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import kotlin.time.Duration.Companion.seconds

internal class TelegramBotRunner(
    private val client: TelegramClient,
    private val botToken: String,
    private val delivery: TelegramDelivery,
    private val agent: AgentRunner,
    private val taskMenu: TaskMenuHandler,
    private val inlineChoices: InlineChoiceHandler,
    private val allowedIds: Set<Long>,
    private val bannedIds: Set<Long>,
    private val voiceTranscriber: VoiceTranscriber?,
    private val profile: BotProfile,
    private val stickerCatalog: StickerCatalog? = null,
    private val groupLog: GroupLogRepository? = null
) {

    private companion object {
        const val MENTION_ONLY_PROMPT = "User mentioned the bot with no text. Respond naturally and briefly."

        // media without a caption is the whole message, and answering it is a conversation move rather
        // than a report; the describe tools exist for when the answer actually depends on the content.
        const val MEDIA_ONLY_PROMPT =
            "User sent this with no caption, so the media itself is their whole message. " +
                    "Reply the way a person would at this point in the conversation. " +
                    "Look at it (`describeImage`, `describeVideo`) or process it (`codeExecution`) only when your answer depends on what is inside, " +
                    "and do not narrate what you saw unless the user asked what is in it."

        // a gif is thrown into a chat the way a sticker is — as a reaction, not as a thing to review.
        const val ANIMATION_ONLY_PROMPT =
            "User answered with a GIF and no caption, the way one reacts with a sticker instead of typing. " +
                    "Treat it as their reaction, match its mood, and keep the conversation going in your own voice. " +
                    "Call `describeVideo` only if they ask what is in it; never describe or narrate it unasked."

        // a round video message is the user talking, so the speech in it is the message, not the picture.
        const val VIDEO_NOTE_ONLY_PROMPT =
            "User sent a video note (a round video message) with no caption — it is them speaking to you. " +
                    "Call `describeVideo` to get what they said, then answer that. " +
                    "Do not describe how the video looks unless they ask."

        const val LOG_PROMPT_MAX_CHARS = 300

        // a rich message may carry 32768 characters where plain text tops out at 4096, and
        // flattening adds markup on top of that. this is the only inbound content without a
        // telegram-side ceiling, so it gets one here.
        const val MAX_RICH_MESSAGE_CHARS = 8_192

        // telegram caps an album at ten items, so a group with that many parts is complete.
        const val MAX_ALBUM_PARTS = 10

        // Telegram clears a chat action after ~5s, so re-assert it just under that.
        val ACTION_REFRESH = 4.seconds

        // album parts arrive as separate updates with a shared media_group_id and no terminator;
        // a group is treated as complete once the update stream stays quiet this long.
        val ALBUM_QUIET_PERIOD = 1.seconds

        val log = KotlinLogging.logger {}
    }

    fun start(scope: CoroutineScope): Job {
        log.info { "Bot started as ${profile.username ?: profile.userId}, allowed ids=${allowedIds.sorted()}" }

        if (allowedIds.isEmpty()) {
            log.warn {
                "ALLOWED_IDS is empty — bot will ignore every message. " +
                        "Set ALLOWED_IDS to user/chat ids that may use the bot."
            }
        }

        if (bannedIds.isNotEmpty()) {
            log.info { "Banned ids=${bannedIds.sorted()}" }

            // an id on both lists is a config mistake worth naming: the ban wins, silently.
            allowedIds.intersect(bannedIds).takeIf { it.isNotEmpty() }?.let {
                log.warn { "ids in both ALLOWED_IDS and BANNED_IDS stay banned: ${it.sorted()}" }
            }
        }

        // the long polling app runs on its own okhttp threads; updates are funneled into a channel so
        // dispatch (and album aggregation) happens inside the coroutine world.
        val updates = Channel<Update>(Channel.UNLIMITED)
        val longPolling = TelegramBotsLongPollingApplication()
        longPolling.registerBot(botToken) { batch -> batch.forEach { updates.trySend(it) } }

        // handlers inherit this dispatcher; without it, they would run on the single-threaded
        // event loop of `suspend main` instead of parallelizing across cores.
        return scope.launch(Dispatchers.Default) {
            try {
                processUpdates(updates, profile)
            } finally {
                runCatching { longPolling.close() }
                    .onFailure { log.warn(it) { "failed to stop long polling cleanly" } }
            }
        }
    }

    // opt-in only for select's onTimeout clause, experimental but long-stable.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun processUpdates(updates: ReceiveChannel<Update>, profile: BotProfile) = supervisorScope {
        val pendingAlbums = linkedMapOf<String, MutableList<Message>>()

        fun flushAlbums() {
            pendingAlbums.values.forEach { parts -> launchHandling(parts.first()) { handleGalleryUpdate(parts, profile) } }
            pendingAlbums.clear()
        }

        while (isActive) {
            val update =
                if (pendingAlbums.isEmpty()) {
                    updates.receiveCatching().getOrNull() ?: break
                } else {
                    // select resolves receive vs timeout atomically; cancelling a suspended receive
                    // (as withTimeout would) can drop an element already taken from the channel.
                    // null on both quiet-period timeout and channel close; either way the buffered
                    // albums are complete, and a closed channel exits on the next iteration.
                    select {
                        updates.onReceiveCatching { it.getOrNull() }
                        onTimeout(ALBUM_QUIET_PERIOD) { null }
                    } ?: run {
                        flushAlbums()
                        continue
                    }
                }

            val callback = update.callbackQuery

            if (callback != null) {
                when {
                    taskMenu.handles(callback.data) -> launchCallbackHandling(callback)
                    inlineChoices.handles(callback.data) -> launchInlineChoiceHandling(callback)
                    // callback data from a scheme this build no longer knows. telegram keeps the
                    // client's button spinning until the query is answered, so answer it anyway.
                    else -> launchUnknownCallbackAnswer(callback)
                }

                continue
            }

            val message = update.message ?: continue

            recordGroupLog(message)
            learnSticker(message)

            val albumKey = message.mediaGroupId?.let { "${message.chatIdLong}:$it" }

            if (albumKey == null) {
                launchHandling(message) { dispatch(message, profile) }
                continue
            }

            val parts = pendingAlbums.getOrPut(albumKey, ::mutableListOf)
            parts += message

            if (parts.size >= MAX_ALBUM_PARTS) {
                pendingAlbums.remove(albumKey)
                launchHandling(message) { handleGalleryUpdate(parts, profile) }
            }
        }

        flushAlbums()
    }

    // the group transcript has to be recorded before [shouldHandle] gets a say, because the messages
    // worth recapping later are exactly the ones nobody addressed to the bot. this also sits ahead of
    // album buffering so each part of a gallery is logged in its own right.
    private fun CoroutineScope.recordGroupLog(message: Message) {
        val repository = groupLog ?: return
        if (message.isPrivateChat || !message.isAllowed()) return

        val entry = message.toGroupLogEntry() ?: return

        launchHandling(message) { repository.record(entry) }
    }

    // a sticker teaches the bot the set it came from even when the message is not addressed to the bot:
    // in a group, stickers people throw at each other are the only view it gets of what they actually use.
    // only the set is recorded, never who sent it or what else the message said.
    private fun CoroutineScope.learnSticker(message: Message) {
        val catalog = stickerCatalog ?: return
        val sticker = message.sticker ?: return
        if (!message.isAllowed()) return

        launchHandling(message) { catalog.observe(message.chatIdLong, sticker) }
    }

    // one bad update must neither kill the polling loop nor cancel sibling handlers.
    private fun CoroutineScope.launchHandling(message: Message, block: suspend () -> Unit) {
        launch {
            runCatching { block() }
                .onFailure { e ->
                    e.rethrowIfCancellation()
                    log.error(e) { "update handling failed for chat=${message.chatIdLong} msg=${message.messageIdLong}" }
                }
        }
    }

    private fun CoroutineScope.launchCallbackHandling(callback: CallbackQuery) {
        launch {
            runCatching { dispatchCallback(callback) }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    log.error(error) {
                        "callback handling failed for chat=${callback.message?.chatId} " +
                                "msg=${callback.message?.messageId} user=${callback.from?.id}"
                    }
                }
        }
    }

    private fun CoroutineScope.launchUnknownCallbackAnswer(callback: CallbackQuery) {
        launch {
            log.warn { "unrecognized callback data=[${callback.data}] user=${callback.from?.id}" }

            runCatching { answerCallbackQuery(client, callback.id) }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    log.warn(error) { "failed to answer unrecognized callback query" }
                }
        }
    }

    private fun CoroutineScope.launchInlineChoiceHandling(callback: CallbackQuery) {
        launch {
            runCatching { dispatchInlineChoiceCallback(callback) }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    log.error(error) {
                        "inline choice handling failed for chat=${callback.message?.chatId} " +
                                "msg=${callback.message?.messageId} user=${callback.from?.id}"
                    }
                }
        }
    }

    private suspend fun dispatch(message: Message, profile: BotProfile) {
        message.logIncoming()

        when {
            message.text != null -> dispatchText(message, profile)
            message.richMessage != null -> handleRichMessageUpdate(message, profile)
            message.sticker != null -> handleStickerUpdate(message, profile)
            message.voice != null -> handleTranscribableUpdate(message, message.voice.toAudioInput(), profile, "voice")
            message.audio != null -> handleTranscribableUpdate(message, message.audio.toAudioInput(), profile, "audio")
            // GIFs carry both `animation` and `document`, so animation has to win over document here.
            message.animation != null ->
                handleMediaUpdate(message, profile, inputKind = "animation", noCaptionPrompt = ANIMATION_ONLY_PROMPT)

            !message.photo.isNullOrEmpty() -> handleMediaUpdate(message, profile, inputKind = "photo")
            message.video != null -> handleMediaUpdate(message, profile, inputKind = "video")

            message.videoNote != null ->
                handleMediaUpdate(message, profile, inputKind = "video note", noCaptionPrompt = VIDEO_NOTE_ONLY_PROMPT)

            message.document != null -> handleMediaUpdate(message, profile, inputKind = "document")
            else -> Unit
        }
    }

    private suspend fun dispatchText(message: Message, profile: BotProfile) {
        val content = message.messageTextOrNull() ?: return
        val command = content.leadingBotCommandOrNull()

        when {
            command == null -> handleTextUpdate(message, content, profile)
            command.matches("start", profile) -> handleStartCommand(message, profile)
            command.matches("tasks", profile) -> handleTasksCommand(message, profile)
            command.matches("clear", profile) -> handleClearCommand(message, profile)
            else -> Unit
        }
    }

    private fun BotCommand.matches(name: String, profile: BotProfile): Boolean =
        command == name &&
                (targetUsername == null || normalizeUsername(targetUsername) == normalizeUsername(profile.username))

    private val Message.language: Language
        get() = Language.fromCode(senderLanguageCodeOrNull())

    private suspend fun handleStartCommand(message: Message, botProfile: BotProfile) {
        if (message.isAccepted(botProfile))
            sendReply(message, Messages.of(message.language).startReply)
    }

    private suspend fun handleTasksCommand(message: Message, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping /tasks without sender user (chat=${message.chatIdLong})" }
                return
            }

        val messages = Messages.of(message.language)

        // the menu is sent straight to the bot api, so unlike an agent reply it has no delivery
        // fallback chain — without this the user would see nothing at all when the send is rejected.
        runCatching {
            taskMenu.sendMenu(
                chatId = message.chatIdLong,
                userId = userId,
                replyToMessageId = message.messageIdLong,
                chatIsPrivate = message.isPrivateChat,
                messages = messages
            )
        }.onFailure { error ->
            error.rethrowIfCancellation()
            log.error(error) { "failed to send task menu for chat=${message.chatIdLong} user=$userId" }
            sendReply(message, messages.fallbackErrorReply)
        }
    }

    private suspend fun handleClearCommand(message: Message, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping /clear without sender user (chat=${message.chatIdLong})" }
                return
            }

        agent.clearConversation(userId, message.chatIdLong)
        sendReply(message, Messages.of(message.language).conversationClearedReply)
    }

    private suspend fun dispatchCallback(callback: CallbackQuery) {
        val messages = Messages.forCode(callback.from?.languageCode)
        val message =
            callback.message ?: run {
                taskMenu.answerUnavailable(callback.id, messages)
                return
            }

        val chatId = message.chatId
        val userId = callback.from.id

        if (!isAllowed(chatId, userId)) {
            log.warn { "denied callback (${denialReason(chatId, userId)}): chat=$chatId user=$userId" }
            taskMenu.answerUnavailable(callback.id, messages)
            return
        }

        taskMenu.handleCallback(
            callbackQueryId = callback.id,
            callbackData = callback.data,
            userId = userId,
            chatId = chatId,
            messageId = message.messageId,
            chatIsPrivate = message.chat.isUserChat,
            messages = messages
        )
    }

    private suspend fun dispatchInlineChoiceCallback(callback: CallbackQuery) {
        val user = callback.from
        val messages = Messages.forCode(user?.languageCode)
        val message =
            callback.message as? Message ?: run {
                inlineChoices.answerUnavailable(callback.id, messages)
                return
            }

        val chatId = message.chatId
        val userId = user.id

        if (!isAllowed(chatId, userId)) {
            log.warn { "denied inline choice callback (${denialReason(chatId, userId)}): chat=$chatId user=$userId" }
            inlineChoices.answerUnavailable(callback.id, messages)
            return
        }

        val selection =
            inlineChoices.handleCallback(
                callbackQueryId = callback.id,
                callbackData = callback.data,
                userId = userId,
                chatId = chatId,
                messageId = message.messageId,
                question = message.text,
                keyboard = message.replyMarkup,
                messages = messages
            ) ?: return

        handleInlineChoiceSelection(message, user, selection, messages)
    }

    private suspend fun handleTextUpdate(message: Message, content: MessageText, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        val userText =
            sanitizeUserText(content, botProfile.userId, botProfile.username)
                .ifBlank { MENTION_ONLY_PROMPT }

        dispatchToAgent(message, userText, botProfile, inputKind = "text")
    }

    private suspend fun handleTranscribableUpdate(
        message: Message,
        audioInput: AudioInput,
        botProfile: BotProfile,
        inputKind: String
    ) {
        if (!message.isAccepted(botProfile)) return

        val caption =
            message.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.username) }
                .orEmpty()

        handleTranscribedAudio(
            message = message,
            audioInput = audioInput,
            caption = caption,
            botProfile = botProfile,
            inputKind = inputKind
        )
    }

    private suspend fun handleTranscribedAudio(
        message: Message,
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
            when (val result = transcriber.transcribe(client, audioInput)) {
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

    // a rich message has no `text`, so the agent gets its flattened markdown instead.
    private suspend fun handleRichMessageUpdate(message: Message, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        val markdown = message.richMessage.toRichMarkdown().limitTo(MAX_RICH_MESSAGE_CHARS)
        if (markdown.isBlank()) return

        dispatchToAgent(message, xmlBlock("rich_message", markdown), botProfile, inputKind = "rich message")
    }

    private suspend fun handleStickerUpdate(message: Message, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return
        val prompt = describeIncomingSticker(message.sticker)
        dispatchToAgent(message, prompt, botProfile, inputKind = "sticker", loadRepliedAttachment = false)
    }

    private suspend fun handleMediaUpdate(
        message: Message,
        botProfile: BotProfile,
        inputKind: String,
        noCaptionPrompt: String = MEDIA_ONLY_PROMPT
    ) {
        if (!message.isAccepted(botProfile)) return

        val caption =
            message.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.username) }
                .orEmpty()
                .ifBlank { noCaptionPrompt }

        dispatchToAgent(
            message,
            caption,
            botProfile,
            inputKind = inputKind,
            attachedFile = message.toAttachedFileOrNull(client)
        )
    }

    // only the first inspectable item becomes the attached file; the model is told about the rest so
    // it does not claim to have looked at every item.
    private suspend fun handleGalleryUpdate(parts: List<Message>, botProfile: BotProfile) {
        val anchor = parts.first()
        val captionedPart = parts.captionedPartOrNull()

        if (!anchor.isAccepted(botProfile, captionSource = captionedPart ?: anchor)) return

        val photoCount = parts.count { !it.photo.isNullOrEmpty() }
        val videoCount = parts.count { it.video != null || it.animation != null }
        val attachedFile = parts.firstNotNullOfOrNull { it.toAttachedFileOrNull(client) }

        val caption =
            captionedPart?.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.username) }
                .orEmpty()
                .ifBlank { MEDIA_ONLY_PROMPT }

        val albumContext =
            xmlBlock(
                "album",
                buildString {
                    append("User sent an album of ${parts.size} media item(s): $photoCount photo(s), $videoCount video(s). ")

                    attachedFile
                        ?.let { append("Only `${it.name}` is available as the attached file; ") }
                        ?: append("None of the items is available as an attached file; ")

                    append("mention this if the request depends on the other items.")
                }
            )

        dispatchToAgent(
            anchor,
            "$albumContext\n\n$caption",
            botProfile,
            inputKind = "gallery",
            attachedFile = attachedFile
        )
    }

    private fun Message.isAccepted(botProfile: BotProfile, captionSource: Message = this): Boolean {
        if (!shouldHandle(this, botProfile.userId, botProfile.username, captionSource))
            return false

        if (!isAllowed()) {
            logDenied(denialReason(chatIdLong, senderIdOrNull()))
            return false
        }

        return true
    }

    private fun Message.isAllowed(): Boolean = isAllowed(chatIdLong, senderIdOrNull())

    private fun isAllowed(chatId: Long, userId: Long?): Boolean = isIdAllowed(chatId, userId, allowedIds, bannedIds)

    private fun denialReason(chatId: Long, userId: Long?): String =
        if (isIdBanned(chatId, userId, bannedIds)) "banned" else "not in allowlist"

    private suspend fun dispatchToAgent(
        message: Message,
        prompt: String,
        botProfile: BotProfile,
        inputKind: String,
        loadRepliedAttachment: Boolean = true,
        attachedFile: AttachedFile? = null
    ) {
        val replyToOtherUser = isReplyToOtherUser(message.replyAuthorIdOrNull(), botProfile.userId)
        val replySummary = if (replyToOtherUser) message.replySummaryOrNull(client, voiceTranscriber) else null

        val effectiveAttachedFile =
            attachedFile
                ?: if (loadRepliedAttachment) replySummary?.let { message.repliedAttachedFileOrNull(client) } else null

        val baseAgentInput = replySummary?.let { formatAgentInput(prompt, it) } ?: prompt

        handleAgentMessage(
            message = message,
            agentInput =
                effectiveAttachedFile?.let { "${attachedFileContextBlock(it)}\n\n$baseAgentInput" } ?: baseAgentInput,
            conversationInput = replySummary?.let { formatConversationInput(prompt, it) } ?: prompt,
            attachedFile = effectiveAttachedFile,
            replyToMessageId = if (replyToOtherUser) message.replyToMessageIdOrNull() else null,
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
                messageContext = message.toMessageContext(loadChatDescription(message)),
                attachedFile = attachedFile,
                language = language
            )

        runAgentTurn(
            request = request,
            inputKind = inputKind,
            waitForTurn = false,
            deliver = { result -> delivery.send(message, result) },
            sendFallback = { sendReply(message, Messages.of(language).fallbackErrorReply) }
        )
    }

    private suspend fun handleInlineChoiceSelection(
        message: Message,
        user: User,
        selection: InlineChoiceSelection,
        messages: Messages
    ) {
        val input = inlineChoiceAgentInput(selection)
        val language = Language.fromCode(user.languageCode)
        val request =
            AgentRequest(
                chatId = message.chatIdLong,
                userId = user.id,
                messageId = 0L,
                prompt = input,
                conversationEntry = input,
                messageContext = message.toMessageContext(user, loadChatDescription(message)),
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
                    userId = user.id,
                    messages = messages
                )
            },
            sendFallback = { sendReply(message, messages.fallbackErrorReply) }
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
            // a live indicator runs through the whole agent turn: it starts as typing and switches to the
            // action of the currently executing tool (upload_photo while an image generates, etc.). delivery
            // then shows its own per-item action. a plain typing action would force typing the whole time.
            val result =
                withLiveChatAction(request.chatId) { setAction ->
                    val onToolStarting = { activity: ToolActivity ->
                        setAction(chatActionFor(activity))
                    }

                    if (waitForTurn)
                        agent.handleQueued(request, onToolStarting)
                    else
                        agent.handle(request, onToolStarting)
                }

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

    // keep a chat action alive for the whole [block], re-asserting it every [ACTION_REFRESH] (Telegram
    // clears an action after a few seconds). [block] receives a setter to switch the action mid-run;
    // [collectLatest] cancels the in-flight refresh loop and re-sends immediately when it changes.
    private suspend fun <T> withLiveChatAction(chatId: Long, block: suspend ((ActionType) -> Unit) -> T): T =
        coroutineScope {
            val action = MutableStateFlow(ActionType.TYPING)

            val ticker =
                launch {
                    action.collectLatest { current ->
                        // collectLatest cancels this block's own child job on a new action, which the
                        // outer launch's isActive would not reflect.
                        while (currentCoroutineContext().isActive) {
                            runCatching { indicateChatAction(chatId, current) }
                                .onFailure { it.rethrowIfCancellation() }

                            delay(ACTION_REFRESH)
                        }
                    }
                }

            try {
                block { action.value = it }
            } finally {
                ticker.cancel()
            }
        }

    private suspend fun indicateChatAction(chatId: Long, action: ActionType) {
        client.api {
            executeAsync(SendChatAction.builder().chatId(chatId).action(action.toString()).build())
        }
    }

    private suspend fun sendReply(message: Message, text: String) {
        TelegramOutputSender.sendText(
            client = client,
            chatId = message.chatIdLong,
            text = text,
            replyParameters = replyParameters(message.messageIdLong)
        )
    }

    private suspend fun loadChatDescription(message: Message): String? {
        if (!message.canLoadChatDescription) return null

        return runCatching {
            client.api { executeAsync(GetChat.builder().chatId(message.chatIdLong).build()) }.description
        }
            .onFailure { error ->
                error.rethrowIfCancellation()
                log.debug(error) { "failed to fetch extended chat context for chat=${message.chatIdLong}" }
            }
            .getOrNull()
    }
}

// an allowlisted chat admits every message in it, including the rare ones without a sender
// (anonymous admins, linked-channel forwards), so the chat check must not depend on the user id.
internal fun isIdAllowed(chatId: Long, userId: Long?, allowedIds: Set<Long>, bannedIds: Set<Long>): Boolean {
    if (isIdBanned(chatId, userId, bannedIds)) return false
    if (chatId in allowedIds) return true
    return userId != null && userId in allowedIds
}

// the ban list wins over the allowlist: someone banned stays banned inside a group that is itself
// allowlisted, which is the only way to shut one person out without closing the chat for everyone.
internal fun isIdBanned(chatId: Long, userId: Long?, bannedIds: Set<Long>): Boolean {
    if (chatId in bannedIds) return true
    return userId != null && userId in bannedIds
}
