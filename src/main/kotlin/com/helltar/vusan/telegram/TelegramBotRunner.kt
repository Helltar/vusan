package com.helltar.vusan.telegram

import com.helltar.vusan.agent.VIDEO_NOTE_ONLY_PROMPT
import com.helltar.vusan.agent.MENTION_ONLY_PROMPT
import com.helltar.vusan.agent.MEDIA_ONLY_PROMPT
import com.helltar.vusan.agent.ANIMATION_ONLY_PROMPT
import kotlin.time.Duration.Companion.nanoseconds
import com.helltar.heartbeat.Heartbeat
import com.helltar.vusan.agent.AgentRunner
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
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.UserRef
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.callback.CallbackRouter
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.callback.TurnStopHandler
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.inbound.AudioInput
import com.helltar.vusan.telegram.inbound.MessageText
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.telegram.inbound.VoiceTranscriptionResult
import com.helltar.vusan.telegram.inbound.ambientCandidateOrNull
import com.helltar.vusan.telegram.inbound.captionedPartOrNull
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.describeIncomingSticker
import com.helltar.vusan.telegram.inbound.isBotCommand
import com.helltar.vusan.telegram.inbound.isEphemeral
import com.helltar.vusan.telegram.inbound.isPrivateChat
import com.helltar.vusan.telegram.inbound.language
import com.helltar.vusan.telegram.inbound.leadingBotCommandOrNull
import com.helltar.vusan.telegram.inbound.logDenied
import com.helltar.vusan.telegram.inbound.logIncoming
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.messageTextOrNull
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
import org.telegram.telegrambots.longpolling.util.DefaultGetUpdatesGenerator
import org.telegram.telegrambots.meta.TelegramUrl
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.polls.PollAnswer
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
    private val accessPolicy: AccessPolicy,
    private val voiceTranscriber: VoiceTranscriber?,
    private val profile: BotProfile,
    private val stickerCatalog: StickerCatalog? = null,
    private val groupLog: GroupLogRepository? = null,
    private val polls: PollRegistry? = null,
    fallbackInUse: () -> FallbackInUse? = { null },
    // answers a group message nobody tagged the bot in when it is meant for it all the same; `null` is off.
    private val ambient: AmbientAddressing? = null,
    private val sandbox: Boolean = false,
) {

    private val heartbeat = Heartbeat()

    private val spool = UpdateSpool(SPOOL_RETENTION)

    private val turns =
        AgentTurns(client, agent, delivery, inlineChoices, chatProfiles, voiceTranscriber, sandbox, fallbackInUse)

    private val turnStop = TurnStopHandler(client, agent)
    private val callbacks = CallbackRouter(client, taskMenu, inlineChoices, turnStop, turns, accessPolicy)
    private val commands = TelegramCommands(delivery, agent, taskMenu)

    private val answeredMessages = AnsweredMessages(ANSWERED_MEMORY)

    fun start(scope: CoroutineScope): Job {
        log.info {
            "Bot started: as=[${profile.username ?: profile.userId}] allowedIds=[${accessPolicy.allowed.sorted().joinToString(", ")}]"
        }

        if (accessPolicy.allowed.isEmpty()) {
            log.warn {
                "ALLOWED_IDS is empty — bot will ignore every message. " +
                        "Set ALLOWED_IDS to user/chat ids that may use the bot."
            }
        }

        if (accessPolicy.banned.isNotEmpty()) {
            log.info { "Banned: ids=[${accessPolicy.banned.sorted().joinToString(", ")}]" }

            // an id on both lists is a config mistake worth naming: the ban wins, silently.
            accessPolicy.contradictory.takeIf { it.isNotEmpty() }?.let {
                log.warn { "ids in both ALLOWED_IDS and BANNED_IDS stay banned: ${it.sorted()}" }
            }
        }

        // the long polling app runs on its own okhttp threads; updates are funneled into a channel so
        // dispatch (and album aggregation) happens inside the coroutine world.
        val updates = Channel<Update>(Channel.UNLIMITED)
        val longPolling = TelegramBotsLongPollingApplication()

        // the generator is where the heartbeat hooks in, because the session calls it once per poll
        // cycle before every request. the consumer below would not do: the session skips it entirely
        // on an empty batch, so a bot nobody writes to would look dead within minutes.
        longPolling.registerBot(
            botToken,
            { TelegramUrl.DEFAULT_URL },
            heartbeat.beatOn(DefaultGetUpdatesGenerator())
        ) { batch ->
            // blocking on purpose, on the session's own poller thread: the batch has to be on disk
            // before this returns, because returning is what lets the next request confirm it to
            // Telegram. the session polls again only after the callback finishes, so nothing else waits.
            runBlocking { spool.record(batch) }

            batch.forEach { updates.trySend(it) }
        }

        // handlers inherit this dispatcher; without it, they would run on the single-threaded
        // event loop of `suspend main` instead of parallelizing across cores.
        return scope.launch(Dispatchers.Default) {
            heartbeat.start()

            client.publishCommandMenu()

            // whatever the last run was handed and never got to. these go in behind anything the first
            // poll already delivered, which only decides the order two answers arrive in — a message
            // answered from both paths is still claimed once, by `AnsweredMessages`.
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

    // opt-in only for select's onTimeout clause, experimental but long-stable.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun processUpdates(updates: ReceiveChannel<Update>, profile: BotProfile) = supervisorScope {
        // each album waits for its own quiet period: a deployment with a few busy chats never has the whole
        // stream go quiet, and an album judged by that would wait on everybody else's messages
        val pendingAlbums = linkedMapOf<String, PendingAlbum>()

        fun flush(albums: Collection<PendingAlbum>) {
            albums.forEach { album -> launchHandling(album.parts.first()) { handleGalleryUpdate(album.parts, profile) } }
        }

        fun flushDue() {
            val due = pendingAlbums.filterValues { it.dueAtNanos <= System.nanoTime() }
            due.keys.forEach(pendingAlbums::remove)
            flush(due.values)
        }

        while (isActive) {
            val update =
                if (pendingAlbums.isEmpty()) {
                    updates.receiveCatching().getOrNull() ?: break
                } else {
                    // select resolves receive vs timeout atomically; canceling a suspended receive
                    // (as withTimeout would) can drop an element already taken from the channel.
                    // null on both the earliest deadline and channel close.
                    val wait = (pendingAlbums.values.minOf { it.dueAtNanos } - System.nanoTime()).nanoseconds

                    select {
                        updates.onReceiveCatching { it.getOrNull() }
                        onTimeout(wait.coerceAtLeast(Duration.ZERO)) { null }
                    } ?: run {
                        if (updates.isClosedForReceive) break

                        flushDue()
                        continue
                    }
                }

            // out of the queue and into this run's hands. an album part is settled here too, while it
            // still waits for its siblings: a turn interrupted mid-flight is not replayed either way,
            // and the spool only ever promises to carry work nobody has started.
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

            if (!message.passesAllowlist(profile)) continue

            recordGroupLog(message)
            learnSticker(message)

            val albumKey = message.mediaGroupId?.let { "${message.chatIdLong}:$it" }

            if (albumKey == null) {
                launchHandling(message) { dispatch(message, profile) }
                continue
            }

            val album = pendingAlbums.getOrPut(albumKey, ::PendingAlbum)
            album.parts += message
            album.dueAtNanos = System.nanoTime() + ALBUM_QUIET_PERIOD.inWholeNanoseconds

            if (album.parts.size >= MAX_ALBUM_PARTS) {
                pendingAlbums.remove(albumKey)
                launchHandling(message) { handleGalleryUpdate(album.parts, profile) }
            }
        }

        flush(pendingAlbums.values)
    }

    // the parts of one gallery seen so far, and when it counts as complete unless another part arrives
    private class PendingAlbum {
        val parts = mutableListOf<Message>()
        var dueAtNanos = 0L
    }

    // the group transcript has to be recorded before [shouldHandle] gets a say, because the messages
    // worth recapping later are exactly the ones nobody addressed to the bot. this also sits ahead of
    // album buffering so each part of a gallery is logged in its own right.
    private fun CoroutineScope.recordGroupLog(message: Message, edited: Boolean = false) {
        val repository = groupLog ?: return
        // an ephemeral command was said to the bot alone, so it is nothing the chat heard
        if (message.isPrivateChat || message.isEphemeral) return

        val entry = message.toGroupLogEntry() ?: return

        // an edit rewrites what the chat says: left alone, a later recap keeps quoting text that is no
        // longer there. the guards and the mapping above are the same either way, so only the sink differs.
        launchHandling(message) {
            if (edited) repository.recordEdit(entry) else repository.record(entry)
        }
    }

    // a vote is not a message: it reaches no dispatch path, appears in no chat, and is answered by
    // nobody. without this the bot asks a question in a group and never learns what anyone said back,
    // so it goes into the transcript the same way a person's message does.
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

    // a sticker teaches the bot the set it came from even when the message is not addressed to the bot:
    // in a group, stickers people throw at each other are the only view it gets of what they actually use.
    // only the set is recorded, never who sent it or what else the message said.
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

        // a reply to one of the bot's ephemeral answers is ephemeral itself, and the agent answers in the
        // open: only the task menu is shown for one person's eyes, anything else is asked to be said
        // aloud. an ephemeral message is always a person's own, so the note has somebody to reach.
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
            commands.recognizes(command, profile) -> if (message.isAccepted(profile)) commands.handle(command, message)
        }
    }

    private suspend fun handleTextUpdate(message: Message, content: MessageText, botProfile: BotProfile) {
        val acceptance = message.acceptance(botProfile) ?: return

        val userText =
            sanitizeUserText(content, botProfile.userId, botProfile.username)
                .ifBlank { MENTION_ONLY_PROMPT }

        turns.dispatchToAgent(message, userText, botProfile, inputKind = "text", ambient = acceptance.ambient)
    }

    private suspend fun handleTranscribableUpdate(
        message: Message,
        audioInput: AudioInput,
        botProfile: BotProfile,
        inputKind: String,
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

        // the message is already claimed, so the person who addressed the bot gets told, not silence
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

    // a rich message has no `text`, so the agent gets its flattened markdown instead.
    private suspend fun handleRichMessageUpdate(message: Message, botProfile: BotProfile) {
        if (!message.isAccepted(botProfile)) return

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
        if (!message.isAccepted(botProfile)) return
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
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.username) }
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

    // every inspectable item travels with the turn, but only image editing takes them all; the model is
    // told which tools see the rest so it does not claim to have looked at every item.
    private suspend fun handleGalleryUpdate(parts: List<Message>, botProfile: BotProfile) {
        val anchor = parts.first()
        val captionedPart = parts.captionedPartOrNull()

        val acceptance = anchor.acceptance(botProfile, captionSource = captionedPart ?: anchor) ?: return

        val photoCount = parts.count { !it.photo.isNullOrEmpty() }
        val videoCount = parts.count { it.video != null || it.animation != null }
        val attachedFiles = parts.mapNotNull { it.toAttachedFileOrNull(client) }

        val caption =
            captionedPart?.messageTextOrNull()
                ?.let { sanitizeUserText(it, botProfile.userId, botProfile.username) }
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

    // nothing outside the allowlist is worth a single cycle: a chat the bot merely sits in must not cost a
    // transcript row, a sticker set lookup, an album buffer or a dispatch coroutine. so this runs on the
    // polling loop, ahead of all of them, and every path into [isAccepted] is behind it.
    private fun Message.passesAllowlist(botProfile: BotProfile): Boolean {
        if (accessPolicy.allows(telegramChat(chatIdLong), senderRefOrNull())) return true

        // only a message aimed at the bot is worth a line — the rest is chat traffic it happens to see.
        if (shouldHandle(this, botProfile.userId, botProfile.username))
            logDenied(accessPolicy.denialReason(telegramChat(chatIdLong), senderRefOrNull()))

        return false
    }

    // the single gate every dispatched message goes through, so it is also where a message is claimed for
    // its one turn: the alternative is remembering to do that at each of the callers.
    private fun Message.isAccepted(botProfile: BotProfile, captionSource: Message = this): Boolean =
        shouldHandle(this, botProfile.userId, botProfile.username, captionSource) && claimForTurn()

    // the same gate for the paths a message nobody tagged the bot in may take too. `null` means the bot stays
    // out. ambient addressing is asked only once everything that calls the bot outright has said no, so it
    // can add answers but never take one away, and nothing it costs falls on a message the bot answers today.
    private suspend fun Message.acceptance(botProfile: BotProfile, captionSource: Message = this): Acceptance? {
        val acceptance =
            when {
                shouldHandle(this, botProfile.userId, botProfile.username, captionSource) -> Acceptance.ADDRESSED
                isAmbientlyAddressed(captionSource) -> Acceptance.AMBIENT
                else -> return null
            }

        return acceptance.takeIf { claimForTurn() }
    }

    private suspend fun Message.isAmbientlyAddressed(captionSource: Message): Boolean {
        val addressing = ambient ?: return false
        val sender = senderIdOrNull() ?: return false
        val waiting = agent.hasTurnUnderWay(conversationScopeOf(sender))
        val candidate = ambientCandidateOrNull(captionSource, authorWaiting = waiting) ?: return false

        return addressing.isAddressed(candidate)
    }

    private fun Message.claimForTurn(): Boolean {
        // an edit reaches the agent through the same path as a new message, so without this the two are
        // indistinguishable in the log. it sits behind the addressing check because an edit of a message
        // nobody aimed at the bot starts nothing, and ahead of the claim so a refused duplicate is labeled.
        if (editDate != null) log.info { "edit reaches the agent: chat=$chatIdLong msg=$messageIdLong" }

        // telegram hands the same message over more than once — as an edit of it, and as a plain
        // redelivery under a fresh update id, which the polling session's own duplicate filter misses. the
        // second turn would repeat an answer into a conversation that has moved on since.
        // an ephemeral command has no message id to claim, and its own id is reused once it expires
        if (isEphemeral) return true

        if (!answeredMessages.markAnswered(chatIdLong, messageIdLong, Instant.now())) {
            log.warn { "skipping a message already answered: chat=$chatIdLong msg=$messageIdLong" }
            return false
        }

        return true
    }

    // how a message came to be the bot's to answer: somebody called it outright, or ambient addressing
    // judged a message nobody tagged it in to be meant for it.
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

        // how late a message left over from the previous run may still be answered. past it the
        // conversation has moved on, and an answer to what someone said hours ago reads worse than
        // the silence they already got. shorter than telegram's own 24-hour hold on purpose.
        val SPOOL_RETENTION = 30.minutes

        val log = KotlinLogging.logger {}
    }
}

/**
 * Whether an edited message should start a turn of its own. It may only do so when the edit is what made
 * the message addressed to the bot — someone adding the mention they forgot.
 *
 * The edited message is already past the allowlist by the time this is asked, and the caller still runs it
 * through `shouldHandle` and the one-turn-per-message claim, so this only decides what an edit itself changes.
 */
internal fun startsTurnOnEdit(
    sentAt: Instant,
    editedAt: Instant?,
    now: Instant,
    window: Duration,
    isCommand: Boolean,
    inAlbum: Boolean,
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

internal fun Message.senderRefOrNull(): UserRef? = senderIdOrNull()?.let(::telegramUser)

internal fun Message.conversationScopeOf(userId: Long): ConversationScope =
    ConversationScope(telegramUser(userId), telegramChat(chatIdLong))
