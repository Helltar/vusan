package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.request.AttachedFile
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
import org.telegram.telegrambots.meta.api.methods.GetMe
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
    private val voiceTranscriber: VoiceTranscriber?
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

    private data class BotProfile(
        val userId: Long,
        val username: String?
    )

    suspend fun start(scope: CoroutineScope): Job {
        val me = client.api { executeAsync(GetMe()) }
        val profile = BotProfile(userId = me.id, username = me.userName)

        log.info { "Bot started as ${profile.username ?: profile.userId}, allowed ids=${allowedIds.sorted()}" }

        if (allowedIds.isEmpty()) {
            log.warn {
                "ALLOWED_IDS is empty — bot will ignore every message. " +
                        "Set ALLOWED_IDS to user/chat ids that may use the bot."
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

        taskMenu.sendMenu(
            chatId = message.chatIdLong,
            userId = userId,
            replyToMessageId = message.messageIdLong,
            chatIsPrivate = message.isPrivateChat,
            messages = Messages.of(message.language)
        )
    }

    private suspend fun handleClearCommand(message: Message, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping /clear without sender user (chat=${message.chatIdLong})" }
                return
            }

        agent.clearHistory(userId)
        sendReply(message, Messages.of(message.language).historyClearedReply)
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
            log.warn { "denied callback (not in allowlist): chat=$chatId user=$userId" }
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
            log.warn { "denied inline choice callback (not in allowlist): chat=$chatId user=$userId" }
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
            logDenied()
            return false
        }

        return true
    }

    private fun Message.logIncoming() {
        log.debug {
            buildString {
                append("message: chat=$chatIdLong chatType=${promptChatType()} msg=$messageIdLong")
                append(" type=${contentTypeName()}")
                chat.titleOrDisplayName()?.let { append(" chatTitle=[$it]") }
                senderIdOrNull()?.let { append(" user=$it") }
                senderUsernameOrNull()?.let { append(" username=[$it]") }
                senderDisplayNameOrNull()?.let { append(" name=[$it]") }

                sticker?.let { sticker ->
                    append(" sticker=[${sticker.readableFormat()} ${sticker.type ?: "regular"}")
                    sticker.emoji?.let { emoji -> append(" $emoji") }
                    sticker.setName?.let { setName -> append(" set=$setName") }
                    append("]")
                }

                textSnippetOrNull()
                    ?.collapseWhitespaceAndCap(LOG_PROMPT_MAX_CHARS)
                    ?.let { append(" text=[$it]") }
            }
        }
    }

    private fun Message.logDenied() {
        log.warn {
            buildString {
                append("denied (not in allowlist): chat=$chatIdLong user=${senderIdOrNull()} type=${contentTypeName()}")
                senderUsernameOrNull()?.let { append(" username=[$it]") }
                senderDisplayNameOrNull()?.let { append(" name=[$it]") }
                textSnippetOrNull()?.collapseWhitespaceAndCap(LOG_PROMPT_MAX_CHARS)?.let { append(" text=[$it]") }
            }
        }
    }

    private fun Message.isAllowed(): Boolean {
        val userId = senderIdOrNull() ?: return false
        return isAllowed(chatIdLong, userId)
    }

    private fun isAllowed(chatId: Long, userId: Long): Boolean {
        if (allowedIds.isEmpty()) return false
        if (chatId in allowedIds) return true
        return userId in allowedIds
    }

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
            historyInput = replySummary?.let { formatHistoryInput(prompt, it) } ?: prompt,
            attachedFile = effectiveAttachedFile,
            replyToMessageId = if (replyToOtherUser) message.replyToMessageIdOrNull() else null,
            inputKind = inputKind
        )
    }

    private suspend fun handleAgentMessage(
        message: Message,
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

        val language = message.language
        val request =
            AgentRequest(
                chatId = chatId,
                userId = userId,
                messageId = message.messageIdLong,
                replyToMessageId = replyToMessageId,
                prompt = agentInput,
                historyEntry = historyInput,
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
                historyEntry = input,
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
