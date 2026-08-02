package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.ToolActivity
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

// the chat action shown while a tool runs, so a slow media-producing call (image generation,
// video download, speech synthesis) hints at what is coming. the activity itself is resolved in the
// agent layer (`toolActivityFor`); here it is only translated to a concrete Telegram action.
internal fun chatActionFor(activity: ToolActivity): ActionType = when (activity) {
    ToolActivity.PHOTO -> ActionType.UPLOAD_PHOTO
    ToolActivity.VIDEO -> ActionType.UPLOAD_VIDEO
    ToolActivity.VOICE -> ActionType.RECORD_VOICE
    ToolActivity.DOCUMENT -> ActionType.UPLOAD_DOCUMENT
    ToolActivity.TEXT -> ActionType.TYPING
}

data class ScheduledAttribution(
    val creatorMessageId: Long?,
    val headerText: String
)

class TelegramDelivery(private val client: TelegramClient) {

    private companion object {
        const val MAX_CAPTION_CHARS = 1000

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

    private enum class ItemDeliveryOutcome { Ok, ReplyMissing, PrivateBlocked }

    suspend fun send(message: Message, result: AgentResult) {
        dispatch(
            result = result,
            originTarget = DeliveryTarget(chatId = message.chatIdLong, replyToMessageId = message.messageIdLong),
            currentChatTarget = DeliveryTarget(message.chatIdLong),
            senderPrivateChatId = message.senderIdOrNull(),
            messages = Messages.forCode(message.senderLanguageCodeOrNull())
        )
    }

    suspend fun sendScheduled(
        result: AgentResult,
        chatId: Long,
        userId: Long,
        messages: Messages,
        attribution: ScheduledAttribution? = null
    ) {
        val plainTarget = DeliveryTarget(chatId = chatId)

        if (attribution?.creatorMessageId == null) {
            attribution?.let { sendNotice(chatId, it.headerText) }
            dispatch(result, plainTarget, plainTarget, senderPrivateChatId = userId, messages = messages)
            return
        }

        val anchorTarget = DeliveryTarget(chatId, replyToMessageId = attribution.creatorMessageId)

        val replyUnavailable =
            dispatch(result, anchorTarget, plainTarget, senderPrivateChatId = userId, messages = messages)

        if (replyUnavailable) {
            sendNotice(chatId, attribution.headerText)
        }
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

    /** Send a plain-text notice from the bot itself (no reply anchor, no formatting fallback retry chain). */
    suspend fun sendNotice(chatId: Long, text: String) {
        runCatching { TelegramOutputSender.sendText(client, chatId, text, replyParameters = null) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to send notice to chat=$chatId" }
            }
    }

    private suspend fun dispatch(
        result: AgentResult,
        originTarget: DeliveryTarget,
        currentChatTarget: DeliveryTarget,
        senderPrivateChatId: Long?,
        messages: Messages
    ): Boolean {
        val comment = result.comment?.takeIf { it.isNotBlank() }
        var replyUnavailable = false
        var privateBlockedNoticed = false

        suspend fun deliverCommentText(text: String, origin: DeliveryTarget) {
            val deliveredWithoutReply =
                deliverText(
                    text = text,
                    toPrivate = result.commentToPrivate,
                    originTarget = origin,
                    senderPrivateChatId = senderPrivateChatId,
                    messages = messages
                )

            if (deliveredWithoutReply) replyUnavailable = true
        }

        if (result.outputs.isEmpty()) {
            comment?.let { deliverCommentText(text = it, origin = originTarget) }
            return replyUnavailable
        }

        val captionIndex =
            comment?.takeIf { it.length <= MAX_CAPTION_CHARS }?.let { singleCaptionIndex(result.outputs) } ?: -1

        result.outputs.forEachIndexed { index, item ->
            if (index > 0) delay(INTER_MESSAGE_DELAY)

            val caption = comment?.takeIf { index == captionIndex }
            val privateTarget = senderPrivateChatId?.takeIf { item.toPrivate }?.let(::DeliveryTarget)
            val routedToPrivate = privateTarget != null
            val target = privateTarget ?: if (replyUnavailable) currentChatTarget else originTarget
            val deliveryTarget = if (routedToPrivate || replyUnavailable) target.withoutReply() else target

            indicateAction(deliveryTarget.chatId, botActionFor(item.output))

            when (deliverItem(item.output, deliveryTarget, caption, routedToPrivate, currentChatTarget, messages)) {
                ItemDeliveryOutcome.Ok -> Unit
                ItemDeliveryOutcome.ReplyMissing -> replyUnavailable = true
                ItemDeliveryOutcome.PrivateBlocked -> if (!privateBlockedNoticed) {
                    privateBlockedNoticed = true
                    notifyPrivateChatBlocked(originTarget, messages)
                }
            }
        }

        if (captionIndex < 0 && comment != null) {
            // a trailing comment always follows at least one item send above (the empty-outputs case
            // returned earlier), so pace it the same as the loop.
            delay(INTER_MESSAGE_DELAY)
            deliverCommentText(comment, if (replyUnavailable) originTarget.withoutReply() else originTarget)
        }

        return replyUnavailable
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

            log.warn(e) { "failed to send outgoing item to chat=${deliveryTarget.chatId}" }

            return ItemDeliveryOutcome.Ok
        }
    }

    private suspend fun deliverText(
        text: String,
        toPrivate: Boolean,
        originTarget: DeliveryTarget,
        senderPrivateChatId: Long?,
        messages: Messages
    ): Boolean {
        val privateTarget = senderPrivateChatId?.takeIf { toPrivate }?.let(::DeliveryTarget)
        val routedToPrivate = privateTarget != null
        val deliveryTarget = privateTarget ?: originTarget

        try {
            indicateAction(deliveryTarget.chatId, ActionType.TYPING)
            sendReplyText(deliveryTarget, text, messages)
            return false
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            if (!routedToPrivate && deliveryTarget.replyToMessageId != null && e.isReplyMessageNotFound()) {
                sendReplyText(deliveryTarget.withoutReply(), text, messages)
                return true
            }

            if (routedToPrivate && isPrivateChatBlocked(e)) {
                notifyPrivateChatBlocked(originTarget, messages)
            } else {
                log.warn(e) { "failed to send text to chat=${deliveryTarget.chatId}" }
            }

            return false
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
