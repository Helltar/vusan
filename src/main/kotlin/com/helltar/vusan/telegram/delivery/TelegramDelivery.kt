package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.senderIdOrNull
import com.helltar.vusan.telegram.inbound.senderLanguageCodeOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Instant

internal fun replyParameters(replyToMessageId: Long?): ReplyParameters? =
    replyToMessageId?.let { ReplyParameters.builder().messageId(it.toInt()).build() }

// the chat action shown just before an item is delivered, so the user sees "sending photo",
// "recording audio", etc. matching what is about to arrive. reactions are instant and get none.
internal fun botActionFor(output: BotOutput): ActionType? = when (output) {
    is BotOutput.Photo, is BotOutput.PhotoGroup -> ActionType.UPLOAD_PHOTO
    is BotOutput.Document, is BotOutput.DocumentGroup, is BotOutput.Audio -> ActionType.UPLOAD_DOCUMENT
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

data class ScheduledAttribution(
    val creatorMessageId: Long?,
    val headerText: String
)

/**
 * [onStickerRejected] is told the catalog id of a sticker Telegram would not accept. It is a hint, not
 * a verdict: the catalog schedules an early re-read of that set rather than deleting anything here,
 * because a send can fail for reasons that say nothing about the sticker (restricted chat, rate limit).
 */
class TelegramDelivery(
    private val client: TelegramClient,
    private val onStickerRejected: (suspend (Long) -> Unit)? = null,
    private val groupLog: GroupLogRepository? = null
) {

    private companion object {
        const val MAX_CAPTION_CHARS = 1000

        // matches what an inbound message is allowed to cost the transcript.
        const val MAX_BOT_TEXT_CHARS = 2_000

        // pace consecutive sends in a multi-output reply so a batch does not trip Telegram's per-chat
        // rate limit. telegrambots does not retry a send that 429s, so pacing is the only guard here.
        val INTER_MESSAGE_DELAY = 700.milliseconds

        val log = KotlinLogging.logger {}
    }

    private data class DeliveryTarget(val chatId: Long, val replyToMessageId: Long? = null) {

        fun withoutReply(): DeliveryTarget =
            if (replyToMessageId == null)
                this
            else
                copy(replyToMessageId = null)
    }

    private enum class ItemDeliveryOutcome { Ok, ReplyMissing, PrivateBlocked, ChatUnreachable }

    private data class DispatchOutcome(val replyUnavailable: Boolean, val chatUnreachable: Boolean)

    suspend fun send(message: Message, result: AgentResult) {
        dispatch(
            result = result,
            originTarget = DeliveryTarget(chatId = message.chatIdLong, replyToMessageId = message.messageIdLong),
            currentChatTarget = DeliveryTarget(message.chatIdLong),
            senderPrivateChatId = message.senderIdOrNull(),
            messages = Messages.forCode(message.senderLanguageCodeOrNull())
        )
    }

    /** Returns `true` when the chat turned out to be unreachable, so the caller can stop firing into it. */
    suspend fun sendScheduled(
        result: AgentResult,
        chatId: Long,
        userId: Long,
        messages: Messages,
        attribution: ScheduledAttribution? = null
    ): Boolean {
        val plainTarget = DeliveryTarget(chatId = chatId)

        if (attribution?.creatorMessageId == null) {
            attribution?.let { sendNotice(chatId, it.headerText) }

            return dispatch(result, plainTarget, plainTarget, senderPrivateChatId = userId, messages = messages)
                .chatUnreachable
        }

        val anchorTarget = DeliveryTarget(chatId, replyToMessageId = attribution.creatorMessageId)

        val outcome =
            dispatch(result, anchorTarget, plainTarget, senderPrivateChatId = userId, messages = messages)

        if (outcome.replyUnavailable && !outcome.chatUnreachable) {
            sendNotice(chatId, attribution.headerText)
        }

        return outcome.chatUnreachable
    }

    suspend fun sendCallback(
        result: AgentResult,
        message: Message,
        userId: Long,
        messages: Messages
    ) {
        val originTarget = DeliveryTarget(message.chatIdLong, replyToMessageId = message.messageIdLong)

        dispatch(
            result = result,
            originTarget = originTarget,
            currentChatTarget = originTarget.withoutReply(),
            senderPrivateChatId = userId,
            messages = messages
        )
    }

    /**
     * Send a plain-text notice from the bot itself (no reply anchor, no formatting fallback retry chain).
     * Returns `false` only when the chat itself is unreachable; any other failure is logged and reported
     * as sent, since it says nothing about whether the next message would arrive.
     */
    suspend fun sendNotice(chatId: Long, text: String): Boolean =
        runCatching {
            TelegramOutputSender.sendText(client, chatId, text, replyParameters = null)
            true
        }.getOrElse { error ->
            error.rethrowIfCancellation()
            log.warn(error) { "failed to send notice to chat=$chatId" }
            !error.isChatUnreachable()
        }

    private suspend fun dispatch(
        result: AgentResult,
        originTarget: DeliveryTarget,
        currentChatTarget: DeliveryTarget,
        senderPrivateChatId: Long?,
        messages: Messages
    ): DispatchOutcome {
        val comment = result.comment?.takeIf { it.isNotBlank() }
        var replyUnavailable = false
        var chatUnreachable = false
        var privateBlockedNoticed = false

        suspend fun deliverCommentText(text: String, origin: DeliveryTarget) {
            when (
                deliverText(
                    text = text,
                    toPrivate = result.commentToPrivate,
                    originTarget = origin,
                    senderPrivateChatId = senderPrivateChatId,
                    messages = messages
                )
            ) {
                ItemDeliveryOutcome.ReplyMissing -> replyUnavailable = true
                ItemDeliveryOutcome.ChatUnreachable -> chatUnreachable = true
                ItemDeliveryOutcome.Ok, ItemDeliveryOutcome.PrivateBlocked -> Unit
            }
        }

        if (result.outputs.isEmpty()) {
            comment?.let { deliverCommentText(text = it, origin = originTarget) }
            return DispatchOutcome(replyUnavailable, chatUnreachable)
        }

        val captionIndex =
            comment?.takeIf { it.length <= MAX_CAPTION_CHARS }?.let { singleCaptionIndex(result.outputs) } ?: -1

        for ((index, item) in result.outputs.withIndex()) {
            if (index > 0) delay(INTER_MESSAGE_DELAY)

            val caption = comment?.takeIf { index == captionIndex }
            val privateTarget = senderPrivateChatId?.takeIf { item.toPrivate }?.let(::DeliveryTarget)
            val routedToPrivate = privateTarget != null
            val target = privateTarget ?: if (replyUnavailable) currentChatTarget else originTarget
            val deliveryTarget = if (routedToPrivate || replyUnavailable) target.withoutReply() else target

            indicateAction(deliveryTarget.chatId, botActionFor(item.output))

            when (deliverItem(item.output, deliveryTarget, caption, routedToPrivate, currentChatTarget, messages)) {
                ItemDeliveryOutcome.Ok ->
                    recordBotMessage(
                        chatId = deliveryTarget.chatId,
                        routedToPrivate = routedToPrivate,
                        senderPrivateChatId = senderPrivateChatId,
                        text = caption ?: item.output.groupLogText(),
                        descriptor = item.output.groupLogDescriptor(),
                        answering = originTarget.replyToMessageId
                    )

                ItemDeliveryOutcome.ReplyMissing -> replyUnavailable = true
                ItemDeliveryOutcome.PrivateBlocked -> if (!privateBlockedNoticed) {
                    privateBlockedNoticed = true
                    notifyPrivateChatBlocked(originTarget, messages)
                }

                // every remaining item would fail the same way, so stop paying for the round trips.
                ItemDeliveryOutcome.ChatUnreachable -> {
                    chatUnreachable = true
                    break
                }
            }
        }

        if (captionIndex < 0 && comment != null && !chatUnreachable) {
            // a trailing comment always follows at least one item send above (the empty-outputs case
            // returned earlier), so pace it the same as the loop.
            delay(INTER_MESSAGE_DELAY)
            deliverCommentText(comment, if (replyUnavailable) originTarget.withoutReply() else originTarget)
        }

        return DispatchOutcome(replyUnavailable, chatUnreachable)
    }

    private fun singleCaptionIndex(outputs: List<OutboxItem>): Int {
        if (
            outputs.any {
                it.output is BotOutput.Text ||
                        it.output is BotOutput.InlineChoice ||
                        it.output is BotOutput.RichMessage
            }
        ) {
            return -1
        }
        val captionables = outputs.withIndex().filter { it.value.output.acceptsCaption }
        return if (captionables.size == 1) captionables.single().index else -1
    }

    private suspend fun deliverItem(
        item: BotOutput,
        deliveryTarget: DeliveryTarget,
        caption: String?,
        routedToPrivate: Boolean,
        currentChatTarget: DeliveryTarget,
        messages: Messages
    ): ItemDeliveryOutcome {
        try {
            sendOutgoing(deliveryTarget, item, caption, messages)
            return ItemDeliveryOutcome.Ok
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            if (!routedToPrivate && deliveryTarget.replyToMessageId != null && e.isReplyMessageNotFound()) {
                runCatching { sendOutgoing(currentChatTarget, item, caption, messages) }
                    .onFailure { retryError ->
                        retryError.rethrowIfCancellation()

                        log.warn(retryError) {
                            "failed to send outgoing item to chat=${currentChatTarget.chatId} without reply"
                        }
                    }

                return ItemDeliveryOutcome.ReplyMissing
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

            log.warn(e) { "failed to send outgoing item to chat=${deliveryTarget.chatId}" }

            return ItemDeliveryOutcome.Ok
        }
    }

    // the bot's own turn belongs in the group transcript: a recap that shows the questions and not the
    // answers reads as if nobody replied. best-effort — the message is already delivered either way.
    private suspend fun recordBotMessage(
        chatId: Long,
        routedToPrivate: Boolean,
        senderPrivateChatId: Long?,
        text: String?,
        descriptor: String?,
        answering: Long?
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
                    chatId = chatId,
                    messageId = null,
                    kind = GroupLogEntry.BOT_KIND,
                    sentAt = Instant.now(),
                    text = text?.collapseWhitespaceAndCap(MAX_BOT_TEXT_CHARS),
                    descriptor = descriptor,
                    // the anchor is what ties this reply to the message it answers, which is how the
                    // recent-chat slice knows this exchange is already in that user's own history.
                    replyToMessageId = answering
                )
            )
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to record a bot message in the chat log for chat=$chatId" }
        }
    }

    private suspend fun reportRejectedSticker(catalogId: Long) {
        val report = onStickerRejected ?: return

        runCatching { report(catalogId) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to report rejected sticker id=$catalogId to the catalog" }
            }
    }

    private suspend fun deliverText(
        text: String,
        toPrivate: Boolean,
        originTarget: DeliveryTarget,
        senderPrivateChatId: Long?,
        messages: Messages
    ): ItemDeliveryOutcome {
        val privateTarget = senderPrivateChatId?.takeIf { toPrivate }?.let(::DeliveryTarget)
        val routedToPrivate = privateTarget != null
        val deliveryTarget = privateTarget ?: originTarget

        suspend fun record() =
            recordBotMessage(
                chatId = deliveryTarget.chatId,
                routedToPrivate = routedToPrivate,
                senderPrivateChatId = senderPrivateChatId,
                text = text,
                descriptor = null,
                answering = originTarget.replyToMessageId
            )

        try {
            indicateAction(deliveryTarget.chatId, ActionType.TYPING)
            sendReplyText(deliveryTarget, text, messages)
            record()
            return ItemDeliveryOutcome.Ok
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            if (!routedToPrivate && deliveryTarget.replyToMessageId != null && e.isReplyMessageNotFound()) {
                sendReplyText(deliveryTarget.withoutReply(), text, messages)
                record()
                return ItemDeliveryOutcome.ReplyMissing
            }

            if (routedToPrivate && isPrivateChatBlocked(e)) {
                notifyPrivateChatBlocked(originTarget, messages)
                return ItemDeliveryOutcome.PrivateBlocked
            }

            if (!routedToPrivate && e.isChatUnreachable()) {
                log.warn(e) { "chat=${deliveryTarget.chatId} no longer accepts messages from the bot" }
                return ItemDeliveryOutcome.ChatUnreachable
            }

            log.warn(e) { "failed to send text to chat=${deliveryTarget.chatId}" }

            return ItemDeliveryOutcome.Ok
        }
    }

    // best-effort: the indicator is cosmetic, so a failed action must never abort the delivery it precedes.
    private suspend fun indicateAction(chatId: Long, action: ActionType?) {
        action ?: return
        runCatching {
            client.api {
                executeAsync(SendChatAction.builder().chatId(chatId).action(action.toString()).build())
            }
        }.onFailure { it.rethrowIfCancellation() }
    }

    private suspend fun notifyPrivateChatBlocked(originTarget: DeliveryTarget, messages: Messages) {
        runCatching { sendText(originTarget, messages.privateBlockedNotice) }
            .onFailure { it.rethrowIfCancellation() }
    }

    private suspend fun sendText(target: DeliveryTarget, text: String) {
        TelegramOutputSender
            .sendText(
                client,
                target.chatId,
                text,
                replyParameters(target.replyToMessageId)
            )
    }

    private suspend fun sendReplyText(target: DeliveryTarget, text: String, messages: Messages) {
        TelegramOutputSender
            .sendReplyText(
                client,
                target.chatId,
                text,
                replyParameters(target.replyToMessageId),
                messages.formattingAsFileNotice
            )
    }

    private suspend fun sendOutgoing(target: DeliveryTarget, item: BotOutput, caption: String?, messages: Messages) {
        TelegramOutputSender
            .send(
                client,
                item,
                target.chatId,
                replyParameters(target.replyToMessageId),
                caption,
                messages.formattingAsFileNotice
            )
    }

    private fun isPrivateChatBlocked(error: Throwable): Boolean =
        error.isForbidden()
}
