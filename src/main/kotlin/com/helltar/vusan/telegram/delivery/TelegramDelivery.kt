package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.escapeHtml
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.delivery.Attribution
import com.helltar.vusan.delivery.AttributionReason
import com.helltar.vusan.delivery.Destination
import com.helltar.vusan.delivery.DeliveryOutcome
import com.helltar.vusan.delivery.OutputDelivery
import com.helltar.vusan.delivery.TurnDelivery
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.PollRegistry
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.telegramChatId
import com.helltar.vusan.telegram.telegramThreadId
import com.helltar.vusan.telegram.telegramMessageId
import com.helltar.vusan.telegram.telegramUserId
import com.helltar.vusan.telegram.telegramChat
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.forumTopicIdOrNull
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.senderIdOrNull
import com.helltar.vusan.telegram.inbound.language
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.ephemeral.EphemeralMessageParameters
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Instant

internal fun replyParameters(replyToMessageId: Long?): ReplyParameters? =
    replyToMessageId?.let { ReplyParameters.builder().messageId(it.toInt()).build() }

// an ephemeral message has no message id, only one of its own kind, and a reply to it is ephemeral too
internal fun ephemeralReplyParameters(ephemeralMessageId: Int): ReplyParameters =
    ReplyParameters.builder().ephemeralMessageId(ephemeralMessageId).build()

// where an answer to this message belongs. an anchored reply would land in the right topic on its
// own, but everything sent without one — a notice, a scheduled fire, an item after the anchor is
// gone — needs the topic named, or it arrives in the forum's General instead.
internal val Message.chatTarget: ChatTarget
    get() = ChatTarget(chatIdLong, forumTopicIdOrNull)

// the chat action shown just before an item is delivered, so the user sees "sending photo",
// "recording audio", etc. matching what is about to arrive. reactions are instant and get none.
internal fun botActionFor(output: BotOutput): ActionType? = when (output) {
    is BotOutput.Photo, is BotOutput.PhotoGroup -> ActionType.UPLOAD_PHOTO
    is BotOutput.Document, is BotOutput.DocumentGroup -> ActionType.UPLOAD_DOCUMENT
    is BotOutput.Audio, is BotOutput.AudioGroup -> ActionType.UPLOAD_DOCUMENT
    is BotOutput.Video, is BotOutput.Animation -> ActionType.UPLOAD_VIDEO
    is BotOutput.VideoNote -> ActionType.RECORD_VIDEO_NOTE
    is BotOutput.Voice -> ActionType.RECORD_VOICE
    is BotOutput.Text,
    is BotOutput.InlineChoice,
    is BotOutput.RichMessage,
    is BotOutput.Quiz,
    is BotOutput.Poll -> ActionType.TYPING
    // nothing is uploaded for either: a reaction is instant, and a sticker is resent by file_id.
    is BotOutput.Reaction, is BotOutput.Sticker -> null
}

// what the bot said, for the group transcript. only outputs that carry words have text; the rest are
// recorded the same way a person's media is, as a short label saying what arrived.
private fun BotOutput.groupLogText(): String? = when (this) {
    is BotOutput.Text -> text
    is BotOutput.RichMessage -> markdown
    is BotOutput.InlineChoice -> question
    is BotOutput.Quiz -> question
    is BotOutput.Poll -> question
    else -> null
}

private fun BotOutput.groupLogDescriptor(): String? = when (this) {
    is BotOutput.Photo -> "photo"
    is BotOutput.PhotoGroup -> "photo album"
    is BotOutput.Document -> filename
    is BotOutput.DocumentGroup -> "documents"
    is BotOutput.Animation -> "animation"
    is BotOutput.Sticker -> "sticker"
    is BotOutput.Voice -> "voice"
    is BotOutput.VideoNote -> "video note"
    is BotOutput.Video -> "video"
    is BotOutput.Audio -> "audio"
    is BotOutput.AudioGroup -> "tracks"
    // a reaction is not a message in the chat, so it leaves no transcript row at all.
    else -> null
}

// the chat action shown while a tool runs, so a slow media-producing call (image generation,
// video download, speech synthesis) hints at what is coming. the activity itself is resolved in the
// agent layer (`toolActivityFor`); here it is only translated to a concrete Telegram action. anything
// that produces no media — and a turn with no tool running — reads as plain typing.
internal fun chatActionFor(activity: ToolActivity?): ActionType = when (activity) {
    ToolActivity.SEARCHING_IMAGES, ToolActivity.DRAWING -> ActionType.UPLOAD_PHOTO
    ToolActivity.SEARCHING_GIF, ToolActivity.DOWNLOADING_VIDEO -> ActionType.UPLOAD_VIDEO
    ToolActivity.DOWNLOADING_AUDIO, ToolActivity.SENDING_FILE -> ActionType.UPLOAD_DOCUMENT
    ToolActivity.SPEAKING -> ActionType.RECORD_VOICE
    else -> ActionType.TYPING
}

// a destination the shared model addressed, in the terms the Bot API takes.
private val Destination.chatTarget: ChatTarget
    get() = ChatTarget(chat.telegramChatId, telegramThreadId(threadId))

// how Telegram names a person: a public @username where there is one, otherwise a link that opens the
// account behind their display name. it is written as HTML because that is what a notice is parsed as
// — markdown would arrive as its own punctuation — and the name is somebody's own text, so it is
// escaped like any other.
private val Attribution.mention: String
    get() =
        username?.let { "@$it" }
            ?: """<a href="tg://user?id=${person.telegramUserId}">${(displayName ?: person.id).escapeHtml()}</a>"""

// which of the two lines to write follows from the reason the task gave; only the mention inside it
// is Telegram's.
private fun Attribution.headerText(messages: Messages): String =

    when (reason) {
        AttributionReason.SCHEDULED -> messages.taskScheduledByNotice(mention)
        AttributionReason.FOLLOW_UP -> messages.taskFollowUpNotice(mention)
    }

/**
 * [onStickerRejected] is told the catalog id of a sticker Telegram would not accept. It is a hint, not
 * a verdict: the catalog schedules an early re-read of that set rather than deleting anything here,
 * because a send can fail for reasons that say nothing about the sticker (restricted chat, rate limit).
 * [onStickerSent] is told the chat and catalog id of every sticker that did go out, which is how the
 * catalog keeps the shortlist from offering the same one again soon.
 */
class TelegramDelivery(
    private val client: TelegramClient,
    private val onStickerRejected: (suspend (Long) -> Unit)? = null,
    private val groupLog: GroupLogRepository? = null,
    private val polls: PollRegistry? = null,
    private val onStickerSent: ((chatId: Long, catalogId: Long) -> Unit)? = null,
) : OutputDelivery {

    private data class DeliveryTarget(val chat: ChatTarget, val replyToMessageId: Long? = null) {

        constructor(chatId: Long) : this(ChatTarget(chatId))

        val chatId: Long
            get() = chat.chatId

        fun withoutReply(): DeliveryTarget =
            if (replyToMessageId == null)
                this
            else
                copy(replyToMessageId = null)
    }

    private enum class ItemDeliveryOutcome { Ok, ReplyMissing, PrivateBlocked, ChatUnreachable, Failed }

    private data class DispatchOutcome(val replyUnavailable: Boolean, val chatUnreachable: Boolean) {

        // `replyUnavailable` is about one anchor, not about the chat, so only the second half reaches
        // the caller: everything else is a send this chat happened to refuse, which says nothing about
        // whether the next one would arrive.
        fun asOutcome(): DeliveryOutcome =
            if (chatUnreachable) DeliveryOutcome.Unreachable else DeliveryOutcome.Handled
    }

    suspend fun send(message: Message, result: AgentResult) {
        dispatch(
            result = result,
            originTarget = DeliveryTarget(message.chatTarget, replyToMessageId = message.messageIdLong),
            currentChatTarget = DeliveryTarget(message.chatTarget),
            senderPrivateChatId = message.senderIdOrNull(),
            messages = Messages.of(message.language),
        )
    }

    override suspend fun deliver(delivery: TurnDelivery): DeliveryOutcome {
        val target = delivery.destination.chatTarget
        val messages = Messages.of(delivery.language)
        val recipient = delivery.recipient.telegramUserId
        val plainTarget = DeliveryTarget(target)
        val attribution = delivery.attribution

        if (attribution?.anchorMessageId == null) {
            attribution?.let { notify(delivery.destination, it.headerText(messages)) }

            return dispatch(delivery.result, plainTarget, plainTarget, recipient, messages).asOutcome()
        }

        val anchorTarget = DeliveryTarget(target, replyToMessageId = attribution.anchorMessageId.telegramMessageId)
        val outcome = dispatch(delivery.result, anchorTarget, plainTarget, recipient, messages)

        // the message the task was set up from is gone, so the answer arrived unanchored and the line
        // saying whose it is has to be sent on its own.
        if (outcome.replyUnavailable && !outcome.chatUnreachable) {
            notify(delivery.destination, attribution.headerText(messages))
        }

        return outcome.asOutcome()
    }

    override suspend fun deliverUnprompted(
        destination: Destination,
        outputs: List<BotOutput>,
        anchorMessageId: String?,
    ): DeliveryOutcome {
        val plainTarget = DeliveryTarget(destination.chatTarget)

        return dispatch(
            result = AgentResult(outputs.map { OutboxItem(it, toPrivate = false) }, comment = null),
            originTarget = plainTarget.copy(replyToMessageId = anchorMessageId?.telegramMessageId),
            currentChatTarget = plainTarget,
            senderPrivateChatId = null,
            messages = Messages.of(Language.DEFAULT),
        ).asOutcome()
    }

    /**
     * Writes into the group transcript what [context]'s turn put in the chat while it was still running
     * — its announced plan, an interim message — at the moment it appeared. Left to the turn's delivery,
     * it would carry the time the turn ended, and a turn queued behind this one reads the transcript
     * before that delivery is over.
     */
    suspend fun recordPostedMidTurn(context: RequestContext, text: String) {
        if (context.chat.isPrivate) return

        recordBotMessage(
            chatId = context.chatRef.telegramChatId,
            routedToPrivate = false,
            senderPrivateChatId = null,
            text = text,
            descriptor = null,
            answering = context.messageId?.telegramMessageId,
        )
    }

    /**
     * [message] is the bot's own question, the one the buttons are on. The answer belongs under
     * [originMessageId], the message the exchange started from, and falls back to the question itself only
     * when there was none — a question asked by a turn with no message behind it.
     */
    suspend fun sendCallback(
        result: AgentResult,
        message: Message,
        originMessageId: Long?,
        userId: Long,
        messages: Messages,
    ) {
        val originTarget =
            DeliveryTarget(message.chatTarget, replyToMessageId = originMessageId ?: message.messageIdLong)

        dispatch(
            result = result,
            originTarget = originTarget,
            currentChatTarget = originTarget.withoutReply(),
            senderPrivateChatId = userId,
            messages = messages,
        )
    }

    /**
     * Send a canned reply — a command answer, a voice notice, a fallback after a failed turn. It carries
     * none of the agent-reply machinery: no formatting fallback chain, no private routing, no retry when
     * the anchor is gone. [replyToMessageId] anchors it somewhere other than [message] itself, which is
     * what an inline choice needs: the question carries the buttons, but the answer belongs under the
     * message that asked.
     */
    suspend fun sendReply(message: Message, text: String, replyToMessageId: Long? = null) {
        withFloodWaitRetry(message.chatIdLong) {
            TelegramOutputSender.sendText(
                client = client,
                target = message.chatTarget,
                text = text,
                replyParameters = replyParameters(replyToMessageId ?: message.messageIdLong),
            )
        }
    }

    /**
     * A reply only [message]'s sender sees, as Telegram's ephemeral messages: anchored to the ephemeral
     * message it answers when there is one, and otherwise sent on the bot's own standing in the chat,
     * which Telegram grants an administrator alone — so this may fail, and the caller says what then.
     */
    suspend fun sendForSenderOnly(message: Message, text: String) {
        val receiver = requireNotNull(message.from?.id) { "An ephemeral reply needs a person to receive it" }

        withFloodWaitRetry(message.chatIdLong) {
            sendTextMessage(
                client = client,
                target = message.chatTarget,
                text = text,
                parseMode = ParseMode.HTML,
                replyParameters = message.ephemeralMessageId?.let(::ephemeralReplyParameters),
                ephemeral = EphemeralMessageParameters.builder().receiverUserId(receiver).build(),
            )
        }
    }

    /**
     * Send a plain-text notice from the bot itself (no reply anchor, no formatting fallback retry chain).
     * Only an unreachable chat is reported as such; any other failure is logged and reported as handled,
     * since it says nothing about whether the next message would arrive.
     */
    override suspend fun notify(destination: Destination, text: String): DeliveryOutcome {
        val target = destination.chatTarget

        return runCatching {
            withFloodWaitRetry(target.chatId) {
                TelegramOutputSender.sendText(client, target, text, replyParameters = null)
            }

            DeliveryOutcome.Handled
        }.getOrElse { error ->
            error.rethrowIfCancellation()
            log.warn(error) { "failed to send notice to chat=${target.chatId}" }

            if (error.isChatUnreachable()) DeliveryOutcome.Unreachable else DeliveryOutcome.Handled
        }
    }

    private suspend fun dispatch(
        result: AgentResult,
        originTarget: DeliveryTarget,
        currentChatTarget: DeliveryTarget,
        senderPrivateChatId: Long?,
        messages: Messages,
    ): DispatchOutcome {
        val comment = result.comment?.takeIf { it.isNotBlank() }
        var replyUnavailable = false
        var chatUnreachable = false
        var privateBlockedNoticed = false
        var sentAnything = false

        // one output into the chat or the sender's DM, with the transcript row only a send that happened
        // earns; false when nothing arrived, so a caption that rode on it is not lost with it
        suspend fun deliverOne(output: BotOutput, caption: String?, toPrivate: Boolean): Boolean {
            // pace consecutive sends, but only behind one that was attempted: a turn whose whole outbox was
            // already delivered mid-run leaves the comment as the first thing sent here
            if (sentAnything) delay(INTER_MESSAGE_DELAY)

            sentAnything = true

            val privateTarget = senderPrivateChatId?.takeIf { toPrivate }?.let { DeliveryTarget(it) }
            val routedToPrivate = privateTarget != null
            val target = privateTarget ?: if (replyUnavailable) currentChatTarget else originTarget
            val deliveryTarget = if (routedToPrivate || replyUnavailable) target.withoutReply() else target

            indicateAction(deliveryTarget.chat, botActionFor(output))

            // a poll is remembered only where its answers can be read back: the transcript this feeds
            // covers groups, and the same guard keeps a redirected reply out of it.
            val pollRegistry = polls?.takeIf { !routedToPrivate && deliveryTarget.chatId != senderPrivateChatId }
            val outcome = deliverItem(output, deliveryTarget, caption, routedToPrivate, currentChatTarget, messages, pollRegistry)

            if (outcome == ItemDeliveryOutcome.ReplyMissing) replyUnavailable = true

            when (outcome) {
                ItemDeliveryOutcome.Ok, ItemDeliveryOutcome.ReplyMissing -> {
                    recordBotMessage(
                        chatId = deliveryTarget.chatId,
                        routedToPrivate = routedToPrivate,
                        senderPrivateChatId = senderPrivateChatId,
                        text = caption ?: output.groupLogText(),
                        descriptor = output.groupLogDescriptor(),
                        answering = originTarget.replyToMessageId,
                    )

                    return true
                }

                ItemDeliveryOutcome.PrivateBlocked -> {
                    if (!privateBlockedNoticed) {
                        privateBlockedNoticed = true
                        notifyPrivateChatBlocked(originTarget, messages)
                    }

                    return false
                }

                ItemDeliveryOutcome.ChatUnreachable -> {
                    chatUnreachable = true

                    return false
                }

                ItemDeliveryOutcome.Failed -> return false
            }
        }

        val captionIndex =
            comment?.takeIf { it.length <= MAX_CAPTION_CHARS }?.let { singleCaptionIndex(result.outputs) } ?: -1
        var captionArrived = false

        for ((index, item) in result.outputs.withIndex()) {
            // the turn already put this in the chat while it was still running (a plan it announced
            // before starting the work), and into the transcript at that moment; sending it would repeat it.
            if (item.delivered) continue

            val caption = comment?.takeIf { index == captionIndex }
            val arrived = deliverOne(item.output, caption, item.toPrivate)

            if (caption != null && arrived) captionArrived = true

            // every remaining item would fail the same way, so stop paying for the round trips.
            if (chatUnreachable) break
        }

        // the comment goes out on its own when no item could carry it, and when the one carrying it never
        // arrived: the words are the answer, the media only what they rode on
        if (comment != null && !captionArrived && !chatUnreachable) {
            deliverOne(BotOutput.Text(comment), caption = null, toPrivate = result.commentToPrivate)
        }

        return DispatchOutcome(replyUnavailable, chatUnreachable)
    }

    // an already delivered item is out of the chat's future: it can neither carry the caption nor stop
    // the one media output that still can from taking it.
    private fun singleCaptionIndex(outputs: List<OutboxItem>): Int {
        val pending = outputs.withIndex().filter { !it.value.delivered }

        if (
            pending.any {
                it.value.output is BotOutput.Text ||
                        it.value.output is BotOutput.InlineChoice ||
                        it.value.output is BotOutput.RichMessage
            }
        ) {
            return -1
        }
        val captionables = pending.filter { it.value.output.acceptsCaption }

        return if (captionables.size == 1) captionables.single().index else -1
    }

    private suspend fun deliverItem(
        item: BotOutput,
        deliveryTarget: DeliveryTarget,
        caption: String?,
        routedToPrivate: Boolean,
        currentChatTarget: DeliveryTarget,
        messages: Messages,
        pollRegistry: PollRegistry? = null,
    ): ItemDeliveryOutcome {
        try {
            sendOutgoing(deliveryTarget, item, caption, messages, pollRegistry)
            noteStickerSent(item, deliveryTarget)
            return ItemDeliveryOutcome.Ok
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            if (!routedToPrivate && deliveryTarget.replyToMessageId != null && e.isReplyMessageNotFound()) {
                return deliverWithoutReply(item, currentChatTarget, caption, messages, pollRegistry)
            }

            if (routedToPrivate && isPrivateChatBlocked(e)) {
                return ItemDeliveryOutcome.PrivateBlocked
            }

            if (!routedToPrivate && e.isChatUnreachable()) {
                log.warn(e) { "chat=${deliveryTarget.chatId} no longer accepts messages from the bot" }
                return ItemDeliveryOutcome.ChatUnreachable
            }

            if (item is BotOutput.Sticker && e.isWrongFileIdentifier()) {
                reportRejectedSticker(item.catalogId)
            }

            // every fallback the kind has was tried on the way here, so nothing arrived: the caller must
            // neither record it nor let a caption die with it
            log.warn(e) { "failed to send outgoing item to chat=${deliveryTarget.chatId}" }

            return ItemDeliveryOutcome.Failed
        }
    }

    // the anchor is gone, so the item goes out unanchored; a second refusal is a failure of its own
    private suspend fun deliverWithoutReply(
        item: BotOutput,
        target: DeliveryTarget,
        caption: String?,
        messages: Messages,
        pollRegistry: PollRegistry?,
    ): ItemDeliveryOutcome =
        try {
            sendOutgoing(target, item, caption, messages, pollRegistry)
            noteStickerSent(item, target)
            ItemDeliveryOutcome.ReplyMissing
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            log.warn(e) { "failed to send outgoing item to chat=${target.chatId} without reply" }

            if (e.isChatUnreachable()) ItemDeliveryOutcome.ChatUnreachable else ItemDeliveryOutcome.Failed
        }

    // the bot's own turn belongs in the group transcript: a recap that shows the questions and not the
    // answers reads as if nobody replied. best-effort — the message is already delivered either way.
    private suspend fun recordBotMessage(
        chatId: Long,
        routedToPrivate: Boolean,
        senderPrivateChatId: Long?,
        text: String?,
        descriptor: String?,
        answering: Long?,
    ) {
        val repository = groupLog ?: return

        // a reply redirected to the sender's DM never happened in the group, so the transcript must not
        // claim it did — the exchange is still kept as the origin chat's conversation history. a private
        // chat is never part of the group log to begin with.
        if (routedToPrivate || chatId == senderPrivateChatId) return
        if (text == null && descriptor == null) return

        runCatching {
            repository.record(
                GroupLogEntry(
                    chat = telegramChat(chatId),
                    messageId = null,
                    kind = GroupLogEntry.BOT_KIND,
                    sentAt = Instant.now(),
                    text = text?.collapseWhitespaceAndCap(MAX_BOT_TEXT_CHARS),
                    descriptor = descriptor,
                    // the anchor is what ties this reply to the message it answers, which is how the
                    // recent-chat slice knows this exchange is already in that user's own history.
                    replyToMessageId = answering?.toString(),
                ),
            )
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to record a bot message in the chat log for chat=$chatId" }
        }
    }

    private fun noteStickerSent(item: BotOutput, target: DeliveryTarget) {
        if (item is BotOutput.Sticker) onStickerSent?.invoke(target.chatId, item.catalogId)
    }

    private suspend fun reportRejectedSticker(catalogId: Long) {
        val report = onStickerRejected ?: return

        runCatching { report(catalogId) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to report rejected sticker id=$catalogId to the catalog" }
            }
    }

    // best-effort: the indicator is cosmetic, so a failed action must never abort the delivery it precedes.
    private suspend fun indicateAction(target: ChatTarget, action: ActionType?) {
        action ?: return
        runCatching {
            client.api {
                executeAsync(
                    SendChatAction.builder()
                        .chatId(target.chatId)
                        .messageThreadId(target.messageThreadId)
                        .action(action.toString())
                        .build(),
                )
            }
        }.onFailure { it.rethrowIfCancellation() }
    }

    private suspend fun notifyPrivateChatBlocked(originTarget: DeliveryTarget, messages: Messages) {
        runCatching { sendText(originTarget, messages.privateBlockedNotice) }
            .onFailure { it.rethrowIfCancellation() }
    }

    private suspend fun sendText(target: DeliveryTarget, text: String) {
        withFloodWaitRetry(target.chatId) {
            TelegramOutputSender
                .sendText(
                    client,
                    target.chat,
                    text,
                    replyParameters(target.replyToMessageId),
                )
        }
    }

    private suspend fun sendOutgoing(
        target: DeliveryTarget,
        item: BotOutput,
        caption: String?,
        messages: Messages,
        pollRegistry: PollRegistry? = null,
    ) {
        withFloodWaitRetry(target.chatId) {
            TelegramOutputSender
                .send(
                    client,
                    item,
                    target.chat,
                    replyParameters(target.replyToMessageId),
                    caption,
                    messages.formattingAsFileNotice,
                    onPollSent =
                        pollRegistry?.let { registry ->
                            { pollId -> registry.remember(pollId, target.chatId, item) }
                        },
                )
        }
    }

    private fun isPrivateChatBlocked(error: Throwable): Boolean =
        error.isForbidden()

    private companion object {
        const val MAX_CAPTION_CHARS = 1000

        // matches what an inbound message is allowed to cost the transcript.
        const val MAX_BOT_TEXT_CHARS = 2_000

        // pace consecutive sends in a multi-output reply so a batch does not trip Telegram's per-chat
        // rate limit in the first place. `withFloodWaitRetry` handles the one that trips it anyway,
        // but it costs the wait Telegram names — this keeps most batches from ever paying it.
        val INTER_MESSAGE_DELAY = 700.milliseconds

        val log = KotlinLogging.logger {}
    }
}
