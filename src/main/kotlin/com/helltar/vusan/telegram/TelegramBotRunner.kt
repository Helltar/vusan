package com.helltar.vusan.telegram

import com.helltar.vusan.agent.VIDEO_NOTE_ONLY_PROMPT
import com.helltar.vusan.agent.MENTION_ONLY_PROMPT
import com.helltar.vusan.agent.MEDIA_ONLY_PROMPT
import com.helltar.vusan.agent.ANIMATION_ONLY_PROMPT
import kotlin.time.Duration.Companion.nanoseconds
import com.helltar.heartbeat.Heartbeat
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.ChatFloor
import com.helltar.vusan.llm.FallbackInUse
import com.helltar.vusan.agent.addressing.AmbientAddressing
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.albumContextBlock
import com.helltar.vusan.agent.wrapAudioTranscript
import com.helltar.vusan.agent.wrapRichMessage
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.UserRef
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.callback.CallbackRouter
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.callback.TurnStopHandler
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.delivery.isTelegramUnavailable
import com.helltar.vusan.telegram.inbound.AudioInput
import com.helltar.vusan.telegram.inbound.MessageText
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.telegram.inbound.VoiceTranscriptionResult
import com.helltar.vusan.telegram.inbound.ambientCandidateOrNull
import com.helltar.vusan.telegram.inbound.captionedPartOrNull
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.describeIncomingSticker
import com.helltar.vusan.telegram.inbound.forumTopicIdOrNull
import com.helltar.vusan.telegram.inbound.isBotCommand
import com.helltar.vusan.telegram.inbound.isEphemeral
import com.helltar.vusan.telegram.inbound.isPerson
import com.helltar.vusan.telegram.inbound.isPrivateChat
import com.helltar.vusan.telegram.inbound.language
import com.helltar.vusan.telegram.inbound.leadingBotCommandOrNull
import com.helltar.vusan.telegram.inbound.logDenied
import com.helltar.vusan.telegram.inbound.logIncoming
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.messageTextOrNull
import com.helltar.vusan.telegram.inbound.realReplyOrNull
import com.helltar.vusan.telegram.inbound.sanitizeUserText
import com.helltar.vusan.telegram.inbound.senderIdOrNull
import com.helltar.vusan.telegram.inbound.shouldHandle
import com.helltar.vusan.telegram.inbound.toAttachedFileOrNull
import com.helltar.vusan.telegram.inbound.toAudioInput
import com.helltar.vusan.telegram.inbound.toGroupLogEntry
import com.helltar.vusan.telegram.inbound.toRichMarkdown
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.exceptions.TelegramApiErrorResponseException
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator
import org.telegram.telegrambots.longpolling.util.ExponentialBackOff
import org.telegram.telegrambots.longpolling.util.TelegramOkHttpClientFactory
import org.telegram.telegrambots.meta.TelegramUrl
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.polls.PollAnswer
import org.telegram.telegrambots.meta.generics.TelegramClient
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.concurrent.Executors
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
    private val accessPolicy: AccessPolicy,
    private val voiceTranscriber: VoiceTranscriber?,
    private val profile: BotProfile,
    private val stickerCatalog: StickerCatalog? = null,
    private val groupLog: GroupLogRepository? = null,
    private val polls: PollRegistry? = null,
    fallbackInUse: () -> FallbackInUse? = { null },
    // answers untagged group messages that are meant for the bot anyway; `null` is off.
    private val ambient: AmbientAddressing? = null,
    private val sandbox: Boolean = false,
    private val floor: ChatFloor = ChatFloor(),
    // where the bot api is served: telegram's own server, or a local one the bot was moved to
    private val telegramUrl: TelegramUrl = TelegramUrl.DEFAULT_URL,
) {

    private val heartbeat = Heartbeat()

    private val spool = UpdateSpool(SPOOL_RETENTION)

    private val turns =
        AgentTurns(client, agent, delivery, inlineChoices, chatProfiles, voiceTranscriber, sandbox, fallbackInUse)

    private val turnStop = TurnStopHandler(client, agent)
    private val callbacks = CallbackRouter(client, taskMenu, inlineChoices, turnStop, turns, accessPolicy)
    private val commands = TelegramCommands(delivery, agent, taskMenu)

    private val answeredMessages = AnsweredMessages(ANSWERED_MEMORY)

    suspend fun start(scope: CoroutineScope): Job {
        log.info {
            "Bot started: as=[${profile.usernames.joinToString(", ").ifEmpty { profile.userId.toString() }}] allowedIds=[${accessPolicy.allowed.sorted().joinToString(", ")}]"
        }

        if (accessPolicy.banned.isNotEmpty()) {
            log.info { "Banned: ids=[${accessPolicy.banned.sorted().joinToString(", ")}]" }

            // an id on both lists is a config mistake: the ban wins, so name it.
            accessPolicy.contradictory.takeIf { it.isNotEmpty() }?.let {
                log.warn { "ids in both ALLOWED_IDS and BANNED_IDS stay banned: ${it.sorted()}" }
            }
        }

        // long polling runs on okhttp threads; a channel moves updates into coroutines for dispatch and album
        // aggregation.
        val updates = Channel<Update>(Channel.UNLIMITED)

        // library defaults except the backoff, whose default reaches fifteen minutes between attempts and
        // keeps the bot deaf that long after telegram is back. this one never gives up and stays short enough
        // that a timed-out poll plus the wait beats the heartbeat's staleness limit.
        val longPolling =
            TelegramBotsLongPollingApplication(
                ::ObjectMapper,
                TelegramOkHttpClientFactory.DefaultOkHttpClientCreator(),
                Executors::newSingleThreadScheduledExecutor,
            ) {
                ExponentialBackOff.Builder()
                    .setMaxIntervalMillis(POLL_BACKOFF_CAP.inWholeMilliseconds.toInt())
                    .setMaxElapsedTimeMillis(Int.MAX_VALUE)
                    .build()
            }

        // the heartbeat hooks into the generator, which the session calls before every poll: the consumer is
        // skipped on an empty batch, so a quiet bot would look dead.
        // getMe has already proved the token, so a deleteWebhook failure here is telegram's and is waited
        // out. a session whose start failed stays registered and would be refused a second time, hence the
        // unregister.
        waitingOutTelegram("deleteWebhook", isOutage = { it.isTelegramUnavailable() || it is TelegramApiErrorResponseException }) {
            runInterruptible(Dispatchers.IO) {
                runCatching {
                    longPolling.registerBot(
                        botToken,
                        { telegramUrl },
                        heartbeat.beatOn(DefaultGetUpdatesGenerator()),
                    ) { batch ->
                        // blocking on purpose: the batch must be on disk before returning, because returning
                        // lets the next request confirm it to telegram. the session polls again only after
                        // this, so nothing else waits.
                        runBlocking { spool.record(batch) }

                        batch.forEach { updates.trySend(it) }
                    }
                }.onFailure { runCatching { longPolling.unregisterBot(botToken) } }.getOrThrow()
            }
        }

        // handlers inherit this dispatcher instead of the single-threaded event loop of `suspend main`.
        return scope.launch(Dispatchers.Default) {
            heartbeat.start()

            client.publishCommandMenu()

            // what the last run received and never handled. it queues behind the first poll, which only
            // affects answer order: a message seen on both paths is still claimed once, by
            // `AnsweredMessages`.
            spool.drain().forEach { updates.trySend(it) }

            try {
                processUpdates(updates, profile)
            } finally {
                heartbeat.close()

                runCatching { longPolling.close() }
                    .onFailure { log.warn(it) { "failed to stop long polling cleanly" } }
            }
        }
    }

    // for select's onTimeout, experimental but long-stable.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun processUpdates(updates: ReceiveChannel<Update>, profile: BotProfile) = supervisorScope {
        // every album and every person's run of lines waits for its own quiet period: in a deployment with a
        // few busy chats the whole stream never goes quiet.
        val pending = linkedMapOf<String, PendingBatch>()

        // a run of lines is one message to the agent when any of them calls the bot. an unaddressed run is
        // handled line by line, so ambient addressing judges single lines as before.
        fun flushLines(parts: List<Message>) {
            if (parts.size > 1 && parts.any { shouldHandle(it, profile.userId, profile.usernames) })
                launchHandling(parts.last()) { handleTextBatch(parts, profile) }
            else
                parts.forEach { part -> launchHandling(part) { dispatch(part, profile) } }
        }

        fun flush(batches: Collection<PendingBatch>) {
            batches.forEach { batch ->
                when (batch.kind) {
                    PendingBatch.Kind.ALBUM -> launchHandling(batch.parts.first()) { handleGalleryUpdate(batch.parts, profile) }
                    PendingBatch.Kind.LINES -> flushLines(batch.parts)
                }
            }
        }

        fun flushDue() {
            val due = pending.filterValues { it.dueAtNanos <= System.nanoTime() }
            due.keys.forEach(pending::remove)
            flush(due.values)
        }

        while (isActive) {
            val update =
                if (pending.isEmpty()) {
                    updates.receiveCatching().getOrNull() ?: break
                } else {
                    // select resolves receive vs timeout atomically; canceling a suspended receive (as
                    // withTimeout would) can drop an element already taken. null on both timeout and close.
                    val wait = (pending.values.minOf { it.dueAtNanos } - System.nanoTime()).nanoseconds

                    select {
                        updates.onReceiveCatching { it.getOrNull() }
                        onTimeout(wait.coerceAtLeast(Duration.ZERO)) { null }
                    } ?: run {
                        if (updates.isClosedForReceive) break

                        flushDue()
                        continue
                    }
                }

            // settled here, album parts included: an interrupted turn is never replayed, and the spool only
            // carries work nobody started.
            spool.settle(update.updateId)

            val pollAnswer = update.pollAnswer

            if (pollAnswer != null) {
                recordPollAnswer(pollAnswer)
                continue
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
                if (!edited.passesAllowlist(profile)) continue

                recordGroupLog(edited, edited = true)

                if (edited.startsTurnOnEdit())
                    launchHandling(edited) { dispatch(edited, profile) }

                continue
            }

            val message = update.message ?: continue

            // ahead of the allowlist: lines from people the bot does not answer still show the chat is awake.
            message.reportToFloor()

            if (!message.passesAllowlist(profile)) continue

            recordGroupLog(message)
            learnSticker(message)

            // anything else from this person comes after their pending lines.
            val linesKey = message.linesKeyOrNull()
            val joinsLines = linesKey != null && message.joinsTextBatch()

            if (linesKey != null && !joinsLines) pending.remove(linesKey)?.let { flush(listOf(it)) }

            val albumKey = message.mediaGroupId?.let { "album:${message.chatIdLong}:$it" }

            when {
                albumKey != null -> {
                    val album = pending.getOrPut(albumKey) { PendingBatch(PendingBatch.Kind.ALBUM) }
                    album.parts += message
                    album.dueAtNanos = System.nanoTime() + ALBUM_QUIET_PERIOD.inWholeNanoseconds

                    if (album.parts.size >= MAX_ALBUM_PARTS) {
                        pending.remove(albumKey)
                        launchHandling(message) { handleGalleryUpdate(album.parts, profile) }
                    }
                }

                joinsLines -> {
                    val lines = pending.getOrPut(linesKey) { PendingBatch(PendingBatch.Kind.LINES) }
                    lines.parts += message
                    lines.dueAtNanos = System.nanoTime() + LINES_QUIET_PERIOD.inWholeNanoseconds

                    if (lines.parts.size >= MAX_LINES_PARTS) {
                        pending.remove(linesKey)
                        flushLines(lines.parts)
                    }
                }

                else -> launchHandling(message) { dispatch(message, profile) }
            }
        }

        flush(pending.values)
    }

    // the parts of one album or one run of lines so far, and when the batch counts as complete.
    private class PendingBatch(val kind: Kind) {
        val parts = mutableListOf<Message>()
        var dueAtNanos = 0L

        enum class Kind { ALBUM, LINES }
    }

    // one person's lines in one chat and topic; a message without a sender of its own joins no run.
    private fun Message.linesKeyOrNull(): String? =
        senderIdOrNull()?.let { "lines:$chatIdLong:${forumTopicIdOrNull ?: 0}:$it" }

    // recorded before [shouldHandle], because the messages worth recapping are the ones nobody addressed to
    // the bot, and before album buffering so each part is logged.
    private fun CoroutineScope.recordGroupLog(message: Message, edited: Boolean = false) {
        val repository = groupLog ?: return
        // an ephemeral command was said to the bot alone, not to the chat.
        if (message.isPrivateChat || message.isEphemeral) return

        val entry = message.toGroupLogEntry() ?: return

        // an edit changes what the chat said; otherwise a later recap keeps quoting text that is gone.
        launchHandling(message) {
            if (edited) repository.recordEdit(entry) else repository.record(entry)
        }
    }

    // every person's group line, addressed or not, allowed or not: anyone but the one the bot has been
    // answering hands the floor back. shared accounts (an anonymous admin, a linked channel, another bot)
    // hand nothing back.
    private fun Message.reportToFloor() {
        if (isPrivateChat || senderChat != null) return
        val sender = from?.takeUnless { it.isBot } ?: return

        floor.lineFrom(telegramChat(chatIdLong), forumTopicIdOrNull?.toString(), telegramUser(sender.id))
    }

    // a vote reaches no dispatch path, so without this the bot would ask a question in a group and never
    // learn the answers. it goes into the transcript like a message.
    private fun CoroutineScope.recordPollAnswer(answer: PollAnswer) {
        val repository = groupLog ?: return
        val registry = polls ?: return

        launch {
            runCatching {
                registry.find(answer.pollId)
                    ?.let { answer.toGroupLogEntry(it) }
                    ?.let { repository.record(it) }
            }.onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to record an answer to poll id=[${answer.pollId}]" }
            }
        }
    }

    // learned even from messages not addressed to the bot: in a group, the stickers people send each other
    // are the only view of what they use. only the set is recorded, never who sent it.
    private fun CoroutineScope.learnSticker(message: Message) {
        val catalog = stickerCatalog ?: return
        val sticker = message.sticker ?: return

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

        // a reply to an ephemeral answer is ephemeral too, but the agent answers in the open: only the task
        // menu is private, anything else is asked to be said aloud.
        if (message.isEphemeral && message.messageTextOrNull()?.leadingBotCommandOrNull() == null) {
            runCatching { delivery.sendForSenderOnly(message, Messages.of(message.language).ephemeralChatReply) }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    log.warn { "could not answer an ephemeral message in chat=${message.chatIdLong}: ${error.message}" }
                }

            return
        }

        when {
            message.text != null -> dispatchText(message, profile)
            message.richMessage != null -> handleRichMessageUpdate(message, profile)
            message.sticker != null -> handleStickerUpdate(message, profile)
            message.voice != null -> handleTranscribableUpdate(message, message.voice.toAudioInput(), profile, "voice")
            message.audio != null -> handleTranscribableUpdate(message, message.audio.toAudioInput(), profile, "audio")
            // GIFs carry both `animation` and `document`, so animation wins.
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
            commands.recognizes(command, profile) -> if (message.isAccepted(profile)) commands.handle(command, message)
        }
    }

    // plain texts one person sent in a row (a message split at the length cap, a thought typed in pieces) are
    // answered as one, under the last. addressing reads the first part that calls the bot, and the parts
    // before the anchor are claimed with it, so a redelivery of any starts nothing.
    private suspend fun handleTextBatch(parts: List<Message>, botProfile: BotProfile) {
        val anchor = parts.last()
        val addressedPart = parts.first { shouldHandle(it, botProfile.userId, botProfile.usernames) }
        val acceptance = anchor.acceptance(botProfile, captionSource = addressedPart) ?: return

        parts.dropLast(1).forEach { answeredMessages.markAnswered(it.chatIdLong, it.messageIdLong, Instant.now()) }

        val userText =
            parts.mapNotNull { it.messageTextOrNull() }
                .joinToString("\n") { sanitizeUserText(it, botProfile.userId, botProfile.usernames) }
                .trim()
                .ifBlank { MENTION_ONLY_PROMPT }

        log.info { "${parts.size} lines answered as one: chat=${anchor.chatIdLong} msgs=${parts.map { it.messageIdLong }}" }

        turns.dispatchToAgent(anchor, userText, botProfile, inputKind = "text batch", ambient = acceptance.ambient)
    }

    private suspend fun handleTextUpdate(message: Message, content: MessageText, botProfile: BotProfile) {
        val acceptance = message.acceptance(botProfile) ?: return

        val userText =
            sanitizeUserText(content, botProfile.userId, botProfile.usernames)
                .ifBlank { MENTION_ONLY_PROMPT }

        turns.dispatchToAgent(message, userText, botProfile, inputKind = "text", ambient = acceptance.ambient)
    }

    private suspend fun handleTranscribableUpdate(
        message: Message,
        audioInput: AudioInput,
        botProfile: BotProfile,
        inputKind: String,
    ) {
        if (!message.isAcceptedForTurn(botProfile)) return

        val caption =
            message.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.usernames) }
                .orEmpty()

        handleTranscribedAudio(
            message = message,
            audioInput = audioInput,
            caption = caption,
            botProfile = botProfile,
            inputKind = inputKind,
        )
    }

    private suspend fun handleTranscribedAudio(
        message: Message,
        audioInput: AudioInput,
        caption: String,
        botProfile: BotProfile,
        inputKind: String,
    ) {
        val transcriber = voiceTranscriber

        // the message is already claimed, so tell the person instead of staying silent.
        if (transcriber == null) {
            log.info { "$inputKind message cannot be heard: STT not configured (chat=${message.chatIdLong} user=${message.senderIdOrNull()})" }
            delivery.sendReply(message, Messages.of(message.language).voiceUnsupportedReply)

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

    // a rich message has no `text`; the agent gets its flattened markdown.
    private suspend fun handleRichMessageUpdate(message: Message, botProfile: BotProfile) {
        if (!message.isAcceptedForTurn(botProfile)) return

        val markdown = message.richMessage.toRichMarkdown().limitTo(MAX_RICH_MESSAGE_CHARS)
        if (markdown.isBlank()) return

        turns.dispatchToAgent(
            message,
            wrapRichMessage(markdown),
            botProfile,
            inputKind = "rich message",
        )
    }

    private suspend fun handleStickerUpdate(message: Message, botProfile: BotProfile) {
        if (!message.isAcceptedForTurn(botProfile)) return
        val prompt = describeIncomingSticker(message.sticker)
        turns.dispatchToAgent(message, prompt, botProfile, inputKind = "sticker", loadRepliedAttachment = false)
    }

    private suspend fun handleMediaUpdate(
        message: Message,
        botProfile: BotProfile,
        inputKind: String,
        noCaptionPrompt: String = MEDIA_ONLY_PROMPT,
    ) {
        val acceptance = message.acceptance(botProfile) ?: return

        val caption =
            message.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.usernames) }
                .orEmpty()
                .ifBlank { noCaptionPrompt }

        turns.dispatchToAgent(
            message,
            caption,
            botProfile,
            inputKind = inputKind,
            attachedFiles = listOfNotNull(message.toAttachedFileOrNull(client)),
            ambient = acceptance.ambient,
        )
    }

    // every inspectable item travels with the turn, but only image editing takes them all; the model is told
    // which tools see the rest so it does not claim to have looked at every item.
    private suspend fun handleGalleryUpdate(parts: List<Message>, botProfile: BotProfile) {
        val anchor = parts.first()
        val captionedPart = parts.captionedPartOrNull()

        val acceptance = anchor.acceptance(botProfile, captionSource = captionedPart ?: anchor) ?: return

        val photoCount = parts.count { !it.photo.isNullOrEmpty() }
        val videoCount = parts.count { it.video != null || it.animation != null }
        val attachedFiles = parts.mapNotNull { it.toAttachedFileOrNull(client) }

        val caption =
            captionedPart?.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.usernames) }
                .orEmpty()
                .ifBlank { MEDIA_ONLY_PROMPT }

        val albumContext = albumContextBlock(parts.size, photoCount, videoCount, attachedFiles, sandbox)

        turns.dispatchToAgent(
            anchor,
            "$albumContext\n\n$caption",
            botProfile,
            inputKind = "gallery",
            attachedFiles = attachedFiles,
            ambient = acceptance.ambient,
        )
    }

    // runs on the polling loop ahead of everything else: a chat outside the allowlist must not cost a
    // transcript row, a sticker lookup, an album buffer or a coroutine. every path into [isAccepted] is
    // behind it.
    private fun Message.passesAllowlist(botProfile: BotProfile): Boolean {
        if (accessPolicy.allows(telegramChat(chatIdLong), senderRefOrNull())) return true

        // log only messages aimed at the bot, not all the chat traffic it sees.
        if (shouldHandle(this, botProfile.userId, botProfile.usernames))
            logDenied(accessPolicy.denialReason(telegramChat(chatIdLong), senderRefOrNull()))

        return false
    }

    // the single gate for every dispatched message, so it also claims the message for its one turn.
    private fun Message.isAccepted(botProfile: BotProfile, captionSource: Message = this): Boolean =
        shouldHandle(this, botProfile.userId, botProfile.usernames, captionSource) && claimForTurn()

    // the gate for an agent turn: a person the floor has closed on gets silence, before anything is
    // transcribed or downloaded.
    private fun Message.isAcceptedForTurn(botProfile: BotProfile): Boolean =
        shouldHandle(this, botProfile.userId, botProfile.usernames) && !isFloorClosed() && claimForTurn()

    // the gate for paths an untagged message may also take; `null` means stay out. ambient addressing runs
    // only after everything that calls the bot outright has said no, so it can add answers but never remove
    // one, and costs nothing on messages answered today.
    private suspend fun Message.acceptance(botProfile: BotProfile, captionSource: Message = this): Acceptance? {
        val acceptance =
            when {
                shouldHandle(this, botProfile.userId, botProfile.usernames, captionSource) -> Acceptance.ADDRESSED
                isAmbientlyAddressed(captionSource) -> Acceptance.AMBIENT
                else -> return null
            }

        return acceptance.takeIf { !isFloorClosed() && claimForTurn() }
    }

    private suspend fun Message.isAmbientlyAddressed(captionSource: Message): Boolean {
        val addressing = ambient ?: return false
        val sender = senderIdOrNull() ?: return false
        // the floor is closed either way, so the classifier is not paid for.
        if (floor.isClosed(telegramChat(chatIdLong), forumTopicIdOrNull?.toString(), telegramUser(sender))) return false

        val waiting = agent.hasTurnUnderWay(conversationScopeOf(sender))
        val candidate = ambientCandidateOrNull(captionSource, authorWaiting = waiting) ?: return false

        return addressing.isAddressed(candidate)
    }

    // asked before the claim: the runner would refuse the turn anyway, and here nothing is transcribed or
    // downloaded and no typing indicator opens. keyed on the sender, as the turn would be.
    private fun Message.isFloorClosed(): Boolean {
        val sender = senderIdOrNull() ?: return false
        val closed = floor.isClosed(telegramChat(chatIdLong), forumTopicIdOrNull?.toString(), telegramUser(sender))

        if (closed) log.info { "floor closed: left unanswered, chat=$chatIdLong user=$sender msg=$messageIdLong" }

        return closed
    }

    private fun Message.claimForTurn(): Boolean {
        // otherwise an edit looks like a new message in the log. after the addressing check (an edit nobody
        // aimed at the bot starts nothing) and before the claim, so a refused duplicate is labeled.
        if (editDate != null) log.info { "edit reaches the agent: chat=$chatIdLong msg=$messageIdLong" }

        // telegram delivers one message more than once, as an edit and as a redelivery under a fresh update
        // id that the session's duplicate filter misses; a second turn would repeat an answer. an ephemeral
        // command has no message id to claim, and its own id is reused once it expires.
        if (isEphemeral) return true

        if (!answeredMessages.markAnswered(chatIdLong, messageIdLong, Instant.now())) {
            log.warn { "skipping a message already answered: chat=$chatIdLong msg=$messageIdLong" }
            return false
        }

        return true
    }

    // how a message became the bot's to answer: called outright, or judged by ambient addressing.
    private enum class Acceptance(val ambient: Boolean) {
        ADDRESSED(ambient = false),
        AMBIENT(ambient = true),
    }

    private fun Message.startsTurnOnEdit(): Boolean =
        startsTurnOnEdit(
            sentAt = Instant.ofEpochSecond(date.toLong()),
            editedAt = editDate?.let { Instant.ofEpochSecond(it.toLong()) },
            now = Instant.now(),
            window = EDIT_TURN_WINDOW,
            isCommand = messageTextOrNull()?.let(::isBotCommand) == true,
            inAlbum = mediaGroupId != null,
        )

    private companion object {
        // a rich message may carry 32768 characters (plain text: 4096), plus markup from flattening, and is
        // the only inbound content without a telegram-side cap.
        const val MAX_RICH_MESSAGE_CHARS = 8_192

        // telegram caps an album at ten items.
        const val MAX_ALBUM_PARTS = 10

        // how long an edit may still start a turn. the reply anchors to the original message, so past this
        // the exchange has moved on.
        val EDIT_TURN_WINDOW = 5.minutes

        // telegram redelivers messages and holds an undelivered update for 24 hours, so the memory outlives
        // that.
        val ANSWERED_MEMORY = 24.hours

        // album parts share a media_group_id but have no terminator; the album is complete once the stream
        // stays quiet this long.
        val ALBUM_QUIET_PERIOD = 1.seconds

        // a person's run of lines is one message once quiet this long. every text reply waits this much, so
        // it is no longer than split parts need.
        val LINES_QUIET_PERIOD = 1.seconds
        const val MAX_LINES_PARTS = 10

        // the oldest leftover message from the previous run still answered; an answer hours late reads worse
        // than the silence already given. shorter than telegram's 24-hour hold on purpose.
        val SPOOL_RETENTION = 30.minutes

        // the longest wait between failed polls before jitter, which adds up to half. with the 100-second
        // read timeout, a cycle stays under the heartbeat's three minutes.
        val POLL_BACKOFF_CAP = 30.seconds

        val log = KotlinLogging.logger {}
    }
}

/**
 * Whether an edited message should start a turn: only when the edit is what addressed the message to the bot,
 * someone adding a forgotten mention.
 *
 * The caller still applies `shouldHandle` and the one-turn-per-message claim, so this decides only what the
 * edit itself changes.
 */
internal fun startsTurnOnEdit(
    sentAt: Instant,
    editedAt: Instant?,
    now: Instant,
    window: Duration,
    isCommand: Boolean,
    inAlbum: Boolean,
): Boolean {
    // without an edit_date this is not an edit at all.
    if (editedAt == null) return false

    // the reply lands under the message, so the message's own age decides; a redelivered old message reads
    // the same. edit_date is never earlier, so this bounds the edit too.
    if (now.isAfter(sentAt.plusMillis(window.inWholeMilliseconds))) return false

    // a command is invoked by sending it: an edited-in `/clear` would wipe a history nobody asked to.
    if (isCommand) return false

    // an album is answered as a whole, off the captioned part.
    return !inAlbum
}

/**
 * Whether this message joins a person's run of lines: a plain text on its own, so not a command, reply,
 * forward, edit, media or ephemeral message. Lines from one person in one chat and topic within
 * `LINES_QUIET_PERIOD` of each other are one message to the agent. A shared sender (anonymous admins,
 * channels) never joins: two people would be read as one.
 */
internal fun Message.joinsTextBatch(): Boolean =
    text != null &&
            from?.isPerson == true &&
            editDate == null &&
            !isEphemeral &&
            forwardOrigin == null &&
            realReplyOrNull == null &&
            messageTextOrNull()?.leadingBotCommandOrNull() == null

internal fun Message.senderRefOrNull(): UserRef? = senderIdOrNull()?.let(::telegramUser)

internal fun Message.conversationScopeOf(userId: Long): ConversationScope =
    ConversationScope(telegramUser(userId), telegramChat(chatIdLong))
