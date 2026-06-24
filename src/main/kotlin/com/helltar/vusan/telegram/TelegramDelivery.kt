package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendBotAction
import dev.inmo.tgbotapi.types.*
import dev.inmo.tgbotapi.types.actions.BotAction
import dev.inmo.tgbotapi.types.actions.RecordVideoNoteAction
import dev.inmo.tgbotapi.types.actions.RecordVoiceAction
import dev.inmo.tgbotapi.types.actions.TypingAction
import dev.inmo.tgbotapi.types.actions.UploadDocumentAction
import dev.inmo.tgbotapi.types.actions.UploadPhotoAction
import dev.inmo.tgbotapi.types.actions.UploadVideoAction
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

internal fun Long.toChatIdentifier(): IdChatIdentifier =
    ChatId(RawChatId(this))

internal fun replyParameters(chatId: Long, replyToMessageId: Long?): ReplyParameters? =
    replyToMessageId?.let { ReplyParameters(chatId.toChatIdentifier(), MessageId(it)) }

// the chat action shown just before an item is delivered, so the user sees "sending photo",
// "recording audio", etc. matching what is about to arrive. reactions are instant and get none.
internal fun botActionFor(output: BotOutput): BotAction? = when (output) {
    is BotOutput.Photo, is BotOutput.PhotoGroup -> UploadPhotoAction
    is BotOutput.Document, is BotOutput.DocumentGroup, is BotOutput.Audio -> UploadDocumentAction
    is BotOutput.Video, is BotOutput.Animation -> UploadVideoAction
    is BotOutput.VideoNote -> RecordVideoNoteAction
    is BotOutput.Voice -> RecordVoiceAction
    is BotOutput.Text, is BotOutput.Quiz, is BotOutput.Poll -> TypingAction
    is BotOutput.Reaction -> null
}

// the chat action shown while a tool runs, so a slow media-producing call (image generation,
// video download, speech synthesis) hints at what is coming. the activity itself is resolved in the
// agent layer (`toolActivityFor`); here it is only translated to a concrete Telegram action.
internal fun chatActionFor(activity: ToolActivity): BotAction = when (activity) {
    ToolActivity.PHOTO -> UploadPhotoAction
    ToolActivity.VIDEO -> UploadVideoAction
    ToolActivity.VOICE -> RecordVoiceAction
    ToolActivity.DOCUMENT -> UploadDocumentAction
    ToolActivity.TEXT -> TypingAction
}

data class ScheduledAttribution(
    val creatorMessageId: Long?,
    val headerText: String
)

class TelegramDelivery(private val bot: TelegramBot) {

    private companion object {
        const val MAX_CAPTION_CHARS = 1000

        // pace consecutive sends in a multi-output reply so a batch does not trip Telegram's per-chat
        // rate limit. the reactive limiter in ktgbotapi still retries any send that 429s anyway; this
        // just keeps a normal batch from getting there.
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

    suspend fun send(message: ChatContentMessage<*>, result: AgentResult) {
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

    /** Send a plain-text notice from the bot itself (no reply anchor, no formatting fallback retry chain). */
    suspend fun sendNotice(chatId: Long, text: String) {
        runCatching { TelegramOutputSender.sendText(bot, chatId.toChatIdentifier(), text, replyParameters = null) }
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
        if (outputs.any { it.output is BotOutput.Text }) return -1
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
            indicateAction(deliveryTarget.chatId, TypingAction)
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
    private suspend fun indicateAction(chatId: Long, action: BotAction?) {
        action ?: return
        runCatching { bot.sendBotAction(chatId.toChatIdentifier(), action) }
            .onFailure { it.rethrowIfCancellation() }
    }

    private suspend fun notifyPrivateChatBlocked(originTarget: DeliveryTarget, messages: Messages) {
        runCatching { sendText(originTarget, messages.privateBlockedNotice) }
            .onFailure { it.rethrowIfCancellation() }
    }

    private suspend fun sendText(target: DeliveryTarget, text: String) {
        TelegramOutputSender
            .sendText(
                bot,
                target.chatId.toChatIdentifier(),
                text,
                replyParameters(target.chatId, target.replyToMessageId)
            )
    }

    private suspend fun sendReplyText(target: DeliveryTarget, text: String, messages: Messages) {
        TelegramOutputSender
            .sendReplyText(
                bot,
                target.chatId.toChatIdentifier(),
                text,
                replyParameters(target.chatId, target.replyToMessageId),
                messages.formattingAsFileNotice
            )
    }

    private suspend fun sendOutgoing(target: DeliveryTarget, item: BotOutput, caption: String?, messages: Messages) {
        TelegramOutputSender
            .send(
                bot,
                item,
                target.chatId.toChatIdentifier(),
                replyParameters(target.chatId, target.replyToMessageId),
                caption,
                messages.formattingAsFileNotice
            )
    }

    private fun isPrivateChatBlocked(error: Throwable): Boolean =
        error.isForbidden()
}
