package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.common.collapseWhitespaceAndCap
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
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import kotlin.time.Duration.Companion.seconds

internal class TelegramBotRunner(
    private val client: TelegramClient,
    private val botToken: String,
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

    private suspend fun dispatch(message: Message, profile: BotProfile) {
        message.logIncoming()

        when {
            message.text != null -> dispatchText(message, profile)
            message.sticker != null -> handleStickerUpdate(message, profile)
            message.voice != null -> handleTranscribableUpdate(message, message.voice.toAudioInput(), profile, "voice")
            message.audio != null -> handleTranscribableUpdate(message, message.audio.toAudioInput(), profile, "audio")
            // GIFs carry both `animation` and `document`; they were never handled as documents.
            message.animation != null -> Unit
            !message.photo.isNullOrEmpty() -> handleMediaUpdate(message, profile, inputKind = "photo")
            message.document != null -> handleMediaUpdate(message, profile, inputKind = "document")
            else -> Unit
        }
    }

    private suspend fun dispatchText(message: Message, profile: BotProfile) {
        val content = message.messageTextOrNull() ?: return
        val command = content.leadingBotCommandOrNull()

        when {
            command == null -> handleTextUpdate(message, content, profile)
            command.isStart(profile) -> handleStartCommand(message, profile)
            else -> Unit
        }
    }

    private fun BotCommand.isStart(profile: BotProfile): Boolean =
        command == "start" &&
                (targetUsername == null || normalizeUsername(targetUsername) == normalizeUsername(profile.username))

    private val Message.language: Language
        get() = Language.fromCode(senderLanguageCodeOrNull())

    private suspend fun handleStartCommand(message: Message, botProfile: BotProfile) {
        if (message.isAccepted(botProfile))
            sendReply(message, Messages.of(message.language).startReply)
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

    private suspend fun handleStickerUpdate(message: Message, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return
        val prompt = describeIncomingSticker(message.sticker)
        dispatchToAgent(message, prompt, botProfile, inputKind = "sticker", loadRepliedAttachment = false)
    }

    private suspend fun handleMediaUpdate(message: Message, botProfile: BotProfile, inputKind: String) {
        if (!message.isAccepted(botProfile)) return

        val caption =
            message.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.username) }
                .orEmpty()
                .ifBlank { MEDIA_ONLY_PROMPT }

        dispatchToAgent(
            message,
            caption,
            botProfile,
            inputKind = inputKind,
            attachedFile = message.toAttachedFileOrNull(client)
        )
    }

    // only the first photo is loadable as the attached file; the model is told about the rest so it
    // does not claim to have inspected every item.
    private suspend fun handleGalleryUpdate(parts: List<Message>, botProfile: BotProfile) {
        val anchor = parts.first()
        val captionedPart = parts.captionedPartOrNull()

        if (!anchor.isAccepted(botProfile, captionSource = captionedPart ?: anchor)) return

        val photos = parts.filter { !it.photo.isNullOrEmpty() }

        val caption =
            captionedPart?.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.username) }
                .orEmpty()
                .ifBlank { MEDIA_ONLY_PROMPT }

        val albumContext =
            xmlBlock(
                "album",
                "User sent an album of ${parts.size} media item(s), ${photos.size} of them photo(s). " +
                        "Only the first photo is available as the attached file; " +
                        "mention this if the request depends on the other items."
            )

        dispatchToAgent(
            anchor,
            "$albumContext\n\n$caption",
            botProfile,
            inputKind = "gallery",
            attachedFile = photos.firstOrNull()?.toAttachedFileOrNull(client)
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
        if (allowedIds.isEmpty()) return false
        if (chatIdLong in allowedIds) return true
        val userId = senderIdOrNull() ?: return false
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

        try {
            // a live indicator runs through the whole agent turn: it starts as typing and switches to the
            // action of the currently executing tool (upload_photo while an image generates, etc.). delivery
            // then shows its own per-item action. a plain typing action would force typing the whole time.
            val result =
                withLiveChatAction(chatId) { setAction ->
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
                        ),
                        onToolStarting = { activity -> setAction(chatActionFor(activity)) }
                    )
                }

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
