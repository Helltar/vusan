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
import com.helltar.vusan.request.UserRef
import com.helltar.vusan.telegram.PollRegistry
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.telegramChatId
import com.helltar.vusan.telegram.telegramThreadId
import com.helltar.vusan.telegram.telegramMessageId
import com.helltar.vusan.telegram.telegramUserId
import com.helltar.vusan.telegram.telegramChat
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.forumTopicIdOrNull
import com.helltar.vusan.telegram.inbound.isEphemeral
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

// an ephemeral message has no message id, only an ephemeral one, and a reply to it is ephemeral too
internal fun ephemeralReplyParameters(ephemeralMessageId: Int): ReplyParameters =
    ReplyParameters.builder().ephemeralMessageId(ephemeralMessageId).build()

// where an answer belongs. an anchored reply finds its topic, but anything unanchored (a notice, a scheduled
// fire, an item after the anchor is gone) needs the topic named or lands in the forum's General.
internal val Message.chatTarget: ChatTarget
    get() = ChatTarget(chatIdLong, forumTopicIdOrNull)

// the chat action shown just before an item is delivered ("sending photo", "recording audio"). reactions get
// none.
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
    // a reaction is instant, and a sticker is resent by file_id.
    is BotOutput.Reaction, is BotOutput.Sticker -> null
}

// what the bot said, for the group transcript: text where an output carries words, otherwise a short label,
// as for a person's media.
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
    // a reaction is not a message, so it leaves no row.
    else -> null
}

// the chat action while a tool runs, hinting at slow media (image generation, video download, speech). the
// activity is resolved by `toolActivityFor`; this only maps it to a telegram action. everything else reads as
// typing.
internal fun chatActionFor(activity: ToolActivity?): ActionType = when (activity) {
    ToolActivity.SEARCHING_IMAGES, ToolActivity.DRAWING -> ActionType.UPLOAD_PHOTO
    ToolActivity.SEARCHING_GIF, ToolActivity.DOWNLOADING_VIDEO, ToolActivity.FILMING -> ActionType.UPLOAD_VIDEO
    ToolActivity.DOWNLOADING_AUDIO, ToolActivity.SENDING_FILE -> ActionType.UPLOAD_DOCUMENT
    ToolActivity.SPEAKING -> ActionType.RECORD_VOICE
    else -> ActionType.TYPING
}

private val Destination.chatTarget: ChatTarget
    get() = ChatTarget(chat.telegramChatId, telegramThreadId(threadId))

// a public @username where there is one, otherwise a link to the account behind the display name. HTML
// because notices are parsed as HTML, and the name is escaped as user text.
private val Attribution.mention: String
    get() =
        username?.let { "@$it" }
            ?: """<a href="tg://user?id=${person.telegramUserId}">${(displayName ?: person.id).escapeHtml()}</a>"""

// the reason the task gave picks the line; only the mention is telegram's.
private fun Attribution.headerText(messages: Messages): String =

    when (reason) {
        AttributionReason.SCHEDULED -> messages.taskScheduledByNotice(mention)
        AttributionReason.FOLLOW_UP -> messages.taskFollowUpNotice(mention)
    }

/**
 * [onStickerRejected] gets the catalog id of a sticker telegram refused. A hint, not a verdict: the catalog
 * schedules an early re-read of the set, since a send can fail for reasons unrelated to the sticker
 * (restricted chat, rate limit). [onStickerSent] gets the chat and catalog id of every sticker sent, so the
 * shortlist does not offer it again soon.
 */
class TelegramDelivery(
    private val client: TelegramClient,
    private val onStickerRejected: (suspend (Long) -> Unit)? = null,
    private val groupLog: GroupLogRepository? = null,
    private val polls: PollRegistry? = null,
    private val onStickerSent: ((chatId: Long, catalogId: Long) -> Unit)? = null,
) : OutputDelivery {

    private data class DeliveryTarget(val chat: ChatTarget, val replyToMessageId: Long? = null) {

        val chatId: Long
            get() = chat.chatId

        constructor(chatId: Long) : this(ChatTarget(chatId))

        fun withoutReply(): DeliveryTarget =
            if (replyToMessageId == null)
                this
            else
                copy(replyToMessageId = null)
    }

    private enum class ItemDeliveryOutcome { Ok, ReplyMissing, PrivateBlocked, ChatUnreachable, Unanswered, Failed }

    private data class DispatchOutcome(
        val replyUnavailable: Boolean,
        val chatUnreachable: Boolean,
        val privateBlocked: Boolean,
    ) {

        // `replyUnavailable` is about one anchor, so it never reaches the caller: it says nothing about the
        // next send. a chat that is gone outranks a closed private one, since it stops every task there.
        fun asOutcome(): DeliveryOutcome =
            when {
                chatUnreachable -> DeliveryOutcome.Unreachable
                privateBlocked -> DeliveryOutcome.PrivateChatClosed
                else -> DeliveryOutcome.Handled
            }
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

            return dispatch(delivery.result, plainTarget, plainTarget, recipient, messages, announcePrivateBlocked = false)
                .asOutcome()
        }

        val anchorTarget = DeliveryTarget(target, replyToMessageId = attribution.anchorMessageId.telegramMessageId)

        val outcome =
            dispatch(delivery.result, anchorTarget, plainTarget, recipient, messages, announcePrivateBlocked = false)

        // the task's origin message is gone, so the answer went unanchored and the attribution line goes on
        // its own.
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
     * Records in the group transcript what [context]'s turn posted while still running (an announced plan, an
     * interim message), at the moment it appeared. Left to the turn's delivery it would carry the end time,
     * and a queued turn reads the transcript before that.
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
     * [message] is the bot's question carrying the buttons. The answer goes under [originMessageId], the
     * message the exchange started from, or under the question when a turn had no message behind it.
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
     * Sends a canned reply (a command answer, a voice notice, a fallback after a failed turn) without the
     * agent-reply machinery: no formatting fallbacks, private routing or retry when the anchor is gone.
     * [replyToMessageId] anchors it elsewhere, as an inline choice needs: the question carries the buttons,
     * the answer goes under the asking message.
     */
    suspend fun sendReply(message: Message, text: String, replyToMessageId: Long? = null) {
        // an ephemeral message's id is 0, which no reply can hang under
        val anchor = replyToMessageId ?: message.messageIdLong.takeUnless { message.isEphemeral }

        withFloodWaitRetry(message.chatIdLong) {
            TelegramOutputSender.sendText(
                client = client,
                target = message.chatTarget,
                text = text,
                replyParameters = replyParameters(anchor),
            )
        }
    }

    /**
     * A reply only [message]'s sender sees, as a telegram ephemeral message: anchored to the ephemeral
     * message it answers while still within its 15 seconds, otherwise sent on the bot's own standing, which
     * only an administrator has. So this may fail, and the caller says what then.
     */
    suspend fun sendForSenderOnly(message: Message, text: String) {
        val receiver = requireNotNull(message.from?.id) { "An ephemeral reply needs a person to receive it" }

        withFloodWaitRetry(message.chatIdLong) {
            sendEphemeralWithAnchorFallback(message.ephemeralMessageId?.let(::ephemeralReplyParameters)) { anchor ->
                sendTextMessage(
                    client = client,
                    target = message.chatTarget,
                    text = text,
                    parseMode = ParseMode.HTML,
                    replyParameters = anchor,
                    ephemeral = EphemeralMessageParameters.builder().receiverUserId(receiver).build(),
                )
            }
        }
    }

    /**
     * Sends a plain-text notice from the bot, with no reply anchor and no formatting fallbacks. Only an
     * unreachable chat is reported; any other failure is logged and treated as handled, since it says nothing
     * about the next send.
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

    /** As [notify]; the one refusal reported is the person's own, a chat never opened or blocked. */
    override suspend fun notifyPrivately(person: UserRef, text: String): DeliveryOutcome {
        val chatId = person.telegramUserId

        return runCatching {
            withFloodWaitRetry(chatId) {
                TelegramOutputSender.sendText(client, ChatTarget(chatId), text, replyParameters = null)
            }

            DeliveryOutcome.Handled
        }.getOrElse { error ->
            error.rethrowIfCancellation()
            log.warn(error) { "failed to send notice to the private chat of user=$chatId" }

            if (isPrivateChatBlocked(error)) DeliveryOutcome.PrivateChatClosed else DeliveryOutcome.Handled
        }
    }

    private suspend fun dispatch(
        result: AgentResult,
        originTarget: DeliveryTarget,
        currentChatTarget: DeliveryTarget,
        senderPrivateChatId: Long?,
        messages: Messages,
        // a turn somebody is waiting on says so where they asked; a scheduled one leaves it to its caller,
        // which knows whether the task goes on.
        announcePrivateBlocked: Boolean = true,
    ): DispatchOutcome {
        val comment = result.comment?.takeIf { it.isNotBlank() }
        var replyUnavailable = false
        var chatUnreachable = false
        var privateBlocked = false
        var sentAnything = false

        // one output into the chat or the sender's DM, with a transcript row only for a send that happened;
        // false when nothing arrived, so the caption riding on it is not lost
        suspend fun deliverOne(output: BotOutput, caption: String?, toPrivate: Boolean): Boolean {
            // pace only behind an attempted send: if the whole outbox went out mid-run, the comment is the
            // first thing sent here
            if (sentAnything) delay(INTER_MESSAGE_DELAY)

            sentAnything = true

            val privateTarget = senderPrivateChatId?.takeIf { toPrivate }?.let { DeliveryTarget(it) }
            val routedToPrivate = privateTarget != null
            val target = privateTarget ?: if (replyUnavailable) currentChatTarget else originTarget
            val deliveryTarget = if (routedToPrivate || replyUnavailable) target.withoutReply() else target

            indicateAction(deliveryTarget.chat, botActionFor(output))

            // a poll is remembered only where its answers can be read back: the transcript covers groups, and
            // the same guard keeps a redirected reply out.
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
                    if (!privateBlocked && announcePrivateBlocked) notifyPrivateChatBlocked(originTarget, messages)

                    privateBlocked = true

                    return false
                }

                ItemDeliveryOutcome.ChatUnreachable -> {
                    chatUnreachable = true

                    return false
                }

                // telegram may have shown it without saying so: the caption is not sent again, and the
                // transcript does not claim an unconfirmed message
                ItemDeliveryOutcome.Unanswered -> return true

                ItemDeliveryOutcome.Failed -> return false
            }
        }

        val captionIndex =
            comment?.takeIf { it.length <= MAX_CAPTION_CHARS }?.let { singleCaptionIndex(result.outputs) } ?: -1
        var captionArrived = false

        for ((index, item) in result.outputs.withIndex()) {
            // already posted mid-run (an announced plan) and recorded then; sending it again would repeat it.
            if (item.delivered) continue

            val caption = comment?.takeIf { index == captionIndex }
            val arrived = deliverOne(item.output, caption, item.toPrivate)

            if (caption != null && arrived) captionArrived = true

            // every remaining item would fail the same way.
            if (chatUnreachable) break
        }

        // the comment goes alone when no item could carry it or the carrier never arrived: the words are the
        // answer, the media only rode on them
        if (comment != null && !captionArrived && !chatUnreachable) {
            deliverOne(BotOutput.Text(comment), caption = null, toPrivate = result.commentToPrivate)
        }

        return DispatchOutcome(replyUnavailable, chatUnreachable, privateBlocked)
    }

    // an already delivered item can neither carry the caption nor keep the last media output from taking it.
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

            if (e.isUnanswered()) {
                log.warn(e) { "no answer to an outgoing item in chat=${deliveryTarget.chatId}, not sending it again" }
                return ItemDeliveryOutcome.Unanswered
            }

            // every fallback was tried, so nothing arrived: the caller must neither record it nor drop a
            // caption with it
            log.warn(e) { "failed to send outgoing item to chat=${deliveryTarget.chatId}" }

            return ItemDeliveryOutcome.Failed
        }
    }

    // the anchor is gone, so send unanchored; a second refusal is a failure of its own
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

            when {
                e.isChatUnreachable() -> ItemDeliveryOutcome.ChatUnreachable
                e.isUnanswered() -> ItemDeliveryOutcome.Unanswered
                else -> ItemDeliveryOutcome.Failed
            }
        }

    // a recap showing the questions without the answers reads as if nobody replied. best-effort: the message
    // is delivered either way.
    private suspend fun recordBotMessage(
        chatId: Long,
        routedToPrivate: Boolean,
        senderPrivateChatId: Long?,
        text: String?,
        descriptor: String?,
        answering: Long?,
    ) {
        val repository = groupLog ?: return

        // a reply redirected to the DM never happened in the group, so the transcript must not claim it; the
        // conversation history still keeps it. a private chat is never in the group log.
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
                    // the anchor is how the recent-chat slice knows this exchange is already in that user's
                    // own history.
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

    // best-effort: the indicator is cosmetic and must never abort the delivery.
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

        const val MAX_BOT_TEXT_CHARS = GroupLogEntry.MAX_TEXT_CHARS

        // paces multi-output replies so a batch rarely trips telegram's per-chat rate limit;
        // `withFloodWaitRetry` handles the ones that do, at the cost of the wait telegram names.
        val INTER_MESSAGE_DELAY = 700.milliseconds

        val log = KotlinLogging.logger {}
    }
}
