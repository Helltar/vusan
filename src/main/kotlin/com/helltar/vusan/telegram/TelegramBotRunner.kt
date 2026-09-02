package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.infra.Heartbeat
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.callback.CallbackRouter
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.inbound.AudioInput
import com.helltar.vusan.telegram.inbound.BotCommand
import com.helltar.vusan.telegram.inbound.MessageText
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.telegram.inbound.VoiceTranscriptionResult
import com.helltar.vusan.telegram.inbound.captionedPartOrNull
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.describeIncomingSticker
import com.helltar.vusan.telegram.inbound.isBotCommand
import com.helltar.vusan.telegram.inbound.isPrivateChat
import com.helltar.vusan.telegram.inbound.language
import com.helltar.vusan.telegram.inbound.leadingBotCommandOrNull
import com.helltar.vusan.telegram.inbound.logDenied
import com.helltar.vusan.telegram.inbound.logIncoming
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.messageTextOrNull
import com.helltar.vusan.telegram.inbound.normalizeUsername
import com.helltar.vusan.telegram.inbound.sanitizeUserText
import com.helltar.vusan.telegram.inbound.senderIdOrNull
import com.helltar.vusan.telegram.inbound.shouldHandle
import com.helltar.vusan.telegram.inbound.toAttachedFileOrNull
import com.helltar.vusan.telegram.inbound.toAudioInput
import com.helltar.vusan.telegram.inbound.toGroupLogEntry
import com.helltar.vusan.telegram.inbound.toRichMarkdown
import com.helltar.vusan.telegram.inbound.wrapAudioTranscript
import com.helltar.vusan.tools.sticker.StickerCatalog
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator
import org.telegram.telegrambots.meta.TelegramUrl
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class TelegramBotRunner(
    private val client: TelegramClient,
    private val botToken: String,
    private val delivery: TelegramDelivery,
    private val agent: AgentRunner,
    private val taskMenu: TaskMenuHandler,
    private val inlineChoices: InlineChoiceHandler,
    private val tasks: TasksRepository,
    private val chatProfiles: ChatProfiles,
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

        // a rich message may carry 32768 characters where plain text tops out at 4096, and
        // flattening adds markup on top of that. this is the only inbound content without a
        // telegram-side ceiling, so it gets one here.
        const val MAX_RICH_MESSAGE_CHARS = 8_192

        // telegram caps an album at ten items, so a group with that many parts is complete.
        const val MAX_ALBUM_PARTS = 10

        // how long a message stays open to being answered by an edit of it. the reply anchors to that
        // message, so past this the exchange it belongs to has moved on and there is nothing to answer.
        val EDIT_TURN_WINDOW = 5.minutes

        // how long a message stays known as answered. telegram delivers the same one more than once, and
        // keeps an update it could not hand over for 24 hours, so the memory has to outlive that window.
        val ANSWERED_MEMORY = 24.hours

        // album parts arrive as separate updates with a shared media_group_id and no terminator;
        // a group is treated as complete once the update stream stays quiet this long.
        val ALBUM_QUIET_PERIOD = 1.seconds

        val log = KotlinLogging.logger {}
    }

    private val heartbeat = Heartbeat()

    private val turns = AgentTurns(client, agent, delivery, inlineChoices, chatProfiles, voiceTranscriber)

    private val callbacks = CallbackRouter(client, taskMenu, inlineChoices, turns, allowedIds, bannedIds)

    private val answeredMessages = AnsweredMessages(ANSWERED_MEMORY)

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
        val getUpdates = DefaultGetUpdatesGenerator()

        // the generator is where the heartbeat hooks in, because the session calls it once per poll
        // cycle before every request. the consumer below would not do: the session skips it entirely
        // on an empty batch, so a bot nobody writes to would look dead within minutes.
        longPolling.registerBot(
            botToken,
            { TelegramUrl.DEFAULT_URL },
            { offset ->
                heartbeat.markPoll()
                getUpdates.apply(offset)
            }
        ) { batch -> batch.forEach { updates.trySend(it) } }

        // handlers inherit this dispatcher; without it, they would run on the single-threaded
        // event loop of `suspend main` instead of parallelizing across cores.
        return scope.launch(Dispatchers.Default) {
            val heartbeatJob = heartbeat.launchIn(this)

            client.publishCommandMenu()

            try {
                processUpdates(updates, profile)
            } finally {
                heartbeatJob.cancel()

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
                launchCallbackHandling(callback)
                continue
            }

            val membership = update.myChatMember

            if (membership != null) {
                chatProfiles.forget(membership.chat.id)
                launch { parkTasksOnLostAccess(tasks, membership) }
                continue
            }

            val edited = update.editedMessage

            if (edited != null) {
                recordGroupLog(edited, edited = true)

                if (edited.startsTurnOnEdit()) {
                    // an edit reaches the agent through the same path as a new message, so without this the
                    // two are indistinguishable in the log.
                    log.info { "edit starts a turn: chat=${edited.chatIdLong} msg=${edited.messageIdLong}" }
                    launchHandling(edited) { dispatch(edited, profile) }
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
    private fun CoroutineScope.recordGroupLog(message: Message, edited: Boolean = false) {
        val repository = groupLog ?: return
        if (message.isPrivateChat || !message.isAllowed()) return

        val entry = message.toGroupLogEntry() ?: return

        // an edit rewrites what the chat says: left alone, a later recap keeps quoting text that is no
        // longer there. the guards and the mapping above are the same either way, so only the sink differs.
        launchHandling(message) {
            if (edited) repository.recordEdit(entry) else repository.record(entry)
        }
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
            runCatching { callbacks.route(callback) }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    log.error(error) {
                        "callback handling failed for data=[${callback.data}] chat=${callback.message?.chatId} " +
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
            command.matches(TASKS_COMMAND, profile) -> handleTasksCommand(message, profile)
            command.matches(CLEAR_COMMAND, profile) -> handleClearCommand(message, profile)
            else -> Unit
        }
    }

    private fun BotCommand.matches(name: String, profile: BotProfile): Boolean =
        command == name &&
                (targetUsername == null || normalizeUsername(targetUsername) == normalizeUsername(profile.username))

    private suspend fun handleStartCommand(message: Message, botProfile: BotProfile) {
        if (message.isAccepted(botProfile))
            delivery.sendReply(message, Messages.of(message.language).startReply)
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
            delivery.sendReply(message, messages.fallbackErrorReply)
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
        delivery.sendReply(message, Messages.of(message.language).conversationClearedReply)
    }

    private suspend fun handleTextUpdate(message: Message, content: MessageText, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

        val userText =
            sanitizeUserText(content, botProfile.userId, botProfile.username)
                .ifBlank { MENTION_ONLY_PROMPT }

        turns.dispatchToAgent(message, userText, botProfile, inputKind = "text")
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
                    delivery.sendReply(message, messages.voiceTooLongReply(result.durationSeconds, result.maxSeconds))
                    return
                }

                is VoiceTranscriptionResult.Empty -> {
                    log.info { "$inputKind transcription empty (chat=${message.chatIdLong}): ${result.reason}" }
                    delivery.sendReply(message, messages.voiceEmptyReply)
                    return
                }

                is VoiceTranscriptionResult.Failed -> {
                    delivery.sendReply(message, messages.voiceTranscriptionFailedReply)
                    return
                }
            }

        val prompt = buildTranscribedPrompt(caption, transcript)

        turns.dispatchToAgent(message, prompt, botProfile, inputKind = inputKind)
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

        turns.dispatchToAgent(message, xmlBlock("rich_message", markdown), botProfile, inputKind = "rich message")
    }

    private suspend fun handleStickerUpdate(message: Message, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return
        val prompt = describeIncomingSticker(message.sticker)
        turns.dispatchToAgent(message, prompt, botProfile, inputKind = "sticker", loadRepliedAttachment = false)
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

        turns.dispatchToAgent(
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

        turns.dispatchToAgent(
            anchor,
            "$albumContext\n\n$caption",
            botProfile,
            inputKind = "gallery",
            attachedFile = attachedFile
        )
    }

    // the single gate every inbound path goes through, so it is also where a message is claimed for its
    // one turn: the alternative is remembering to do that at each of the callers.
    private fun Message.isAccepted(botProfile: BotProfile, captionSource: Message = this): Boolean {
        if (!shouldHandle(this, botProfile.userId, botProfile.username, captionSource))
            return false

        if (!isAllowed()) {
            logDenied(denialReason(chatIdLong, senderIdOrNull(), bannedIds))
            return false
        }

        // telegram hands the same message over more than once — as an edit of it, and as a plain
        // redelivery under a fresh update id, which the polling session's own duplicate filter misses. the
        // second turn would repeat an answer into a conversation that has moved on since.
        if (!answeredMessages.markAnswered(chatIdLong, messageIdLong, Instant.now())) {
            log.warn { "skipping a message already answered: chat=$chatIdLong msg=$messageIdLong" }
            return false
        }

        return true
    }

    private fun Message.startsTurnOnEdit(): Boolean =
        startsTurnOnEdit(
            sentAt = Instant.ofEpochSecond(date.toLong()),
            editedAt = editDate?.let { Instant.ofEpochSecond(it.toLong()) },
            now = Instant.now(),
            window = EDIT_TURN_WINDOW,
            isCommand = messageTextOrNull()?.let(::isBotCommand) == true,
            inAlbum = mediaGroupId != null
        )

    private fun Message.isAllowed(): Boolean = isAllowed(chatIdLong, senderIdOrNull())

    private fun isAllowed(chatId: Long, userId: Long?): Boolean = isIdAllowed(chatId, userId, allowedIds, bannedIds)

}

/**
 * Whether an edited message should start a turn of its own. It may only do so when the edit is what made
 * the message addressed to the bot — someone adding the mention they forgot.
 *
 * The caller still runs the edited message through `shouldHandle`, the allowlist and the one-turn-per-message
 * claim, so this only decides what an edit itself changes.
 */
internal fun startsTurnOnEdit(
    sentAt: Instant,
    editedAt: Instant?,
    now: Instant,
    window: Duration,
    isCommand: Boolean,
    inAlbum: Boolean
): Boolean {
    // without an edit_date telegram is not describing an edit at all, whatever else the update carries.
    if (editedAt == null) return false

    // the reply lands under the message, so the message's own age decides: one the chat has left behind
    // gets no new answer however fresh the edit is, and a redelivered old message reads exactly the same
    // way. an edit_date is never earlier than the message, so this bounds the edit itself too.
    if (now.isAfter(sentAt.plusMillis(window.inWholeMilliseconds))) return false

    // a command is invoked by sending it, not by editing a message into one — `/clear` would wipe a
    // history nobody asked it to.
    if (isCommand) return false

    // an album is answered as a whole, off whichever part carries the caption; one edited part is not one.
    return !inAlbum
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

internal fun denialReason(chatId: Long, userId: Long?, bannedIds: Set<Long>): String =
    if (isIdBanned(chatId, userId, bannedIds)) "banned" else "not in allowlist"
