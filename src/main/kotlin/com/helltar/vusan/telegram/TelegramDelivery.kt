package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutput
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.ReplyMessageNotFoundException
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import io.github.oshai.kotlinlogging.KotlinLogging

internal fun Long.toChatIdentifier(): IdChatIdentifier =
    ChatId(RawChatId(this))

internal fun replyParameters(chatId: Long, replyToMessageId: Long?): ReplyParameters? =
    replyToMessageId?.let { ReplyParameters(chatId.toChatIdentifier(), MessageId(it)) }

class TelegramDelivery(private val bot: TelegramBot) {

    private companion object {
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

    suspend fun send(message: CommonMessage<*>, result: AgentResult) {
        dispatch(
            result = result,
            originTarget = DeliveryTarget(chatId = message.chatIdLong, replyToMessageId = message.messageIdLong),
            currentChatTarget = DeliveryTarget(message.chatIdLong),
            senderPrivateChatId = message.senderIdOrNull()
        )
    }

    /**
     * Deliver a result produced by the reminder scheduler — no origin message to reply to,
     * but `senderPrivateChatId` is set so a tool that called `replyInPrivateMessages` can still route to DMs.
     */
    suspend fun sendScheduled(result: AgentResult, chatId: Long, userId: Long) {
        val target = DeliveryTarget(chatId = chatId)
        dispatch(
            result = result,
            originTarget = target,
            currentChatTarget = target,
            senderPrivateChatId = userId
        )
    }

    /** Send a plain-text notice from the bot itself (no reply anchor, no markdown fallback retry chain). */
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
        senderPrivateChatId: Long?
    ) {
        val comment = result.comment?.takeIf { it.isNotBlank() }
        var replyUnavailable = false
        var privateBlockedNoticed = false

        suspend fun deliverCommentText(text: String, origin: DeliveryTarget) {
            val deliveredWithoutReply =
                deliverText(
                    text = text,
                    toPrivate = result.commentToPrivate,
                    originTarget = origin,
                    senderPrivateChatId = senderPrivateChatId
                )

            if (deliveredWithoutReply) replyUnavailable = true
        }

        if (result.outputs.isEmpty()) {
            comment?.let { deliverCommentText(text = it, origin = originTarget) }
            return
        }

        val captionIndex = singleCaptionIndex(result.outputs)

        result.outputs.forEachIndexed { index, item ->
            val caption = comment?.takeIf { index == captionIndex }
            val privateTarget = senderPrivateChatId?.takeIf { item.toPrivate }?.let(::DeliveryTarget)
            val routedToPrivate = privateTarget != null
            val target = privateTarget ?: if (replyUnavailable) currentChatTarget else originTarget
            val deliveryTarget = if (routedToPrivate || replyUnavailable) target.withoutReply() else target

            when (deliverItem(item, deliveryTarget, caption, routedToPrivate, currentChatTarget)) {
                ItemDeliveryOutcome.Ok -> Unit
                ItemDeliveryOutcome.ReplyMissing -> replyUnavailable = true
                ItemDeliveryOutcome.PrivateBlocked -> if (!privateBlockedNoticed) {
                    privateBlockedNoticed = true
                    notifyPrivateChatBlocked(originTarget)
                }
            }
        }

        if (captionIndex < 0 && comment != null) {
            deliverCommentText(comment, originTarget.takeUnless { replyUnavailable } ?: originTarget.withoutReply())
        }
    }

    private fun singleCaptionIndex(outputs: List<BotOutput>): Int {
        if (outputs.any { it is BotOutput.Text }) return -1
        val captionables = outputs.withIndex().filter { it.value.acceptsCaption }
        return if (captionables.size == 1) captionables.single().index else -1
    }

    private suspend fun deliverItem(
        item: BotOutput,
        deliveryTarget: DeliveryTarget,
        caption: String?,
        routedToPrivate: Boolean,
        currentChatTarget: DeliveryTarget
    ): ItemDeliveryOutcome {
        try {
            sendOutgoing(deliveryTarget, item, caption)
            return ItemDeliveryOutcome.Ok
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            if (!routedToPrivate && deliveryTarget.replyToMessageId != null && e is ReplyMessageNotFoundException) {
                runCatching { sendOutgoing(currentChatTarget, item, caption) }
                    .onFailure { retryError ->
                        retryError.rethrowIfCancellation()
                        log.warn(retryError) { "failed to send outgoing item to chat=${currentChatTarget.chatId} without reply" }
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
        senderPrivateChatId: Long?
    ): Boolean {
        val privateTarget = senderPrivateChatId?.takeIf { toPrivate }?.let(::DeliveryTarget)
        val routedToPrivate = privateTarget != null
        val deliveryTarget = privateTarget ?: originTarget

        try {
            sendText(deliveryTarget, text)
            return false
        } catch (e: Throwable) {
            e.rethrowIfCancellation()

            if (!routedToPrivate && deliveryTarget.replyToMessageId != null && e is ReplyMessageNotFoundException) {
                sendText(deliveryTarget.withoutReply(), text)
                return true
            }

            if (routedToPrivate && isPrivateChatBlocked(e)) {
                notifyPrivateChatBlocked(originTarget)
            } else {
                log.warn(e) { "failed to send text to chat=${deliveryTarget.chatId}" }
            }

            return false
        }
    }

    private suspend fun notifyPrivateChatBlocked(originTarget: DeliveryTarget) {
        runCatching { sendText(originTarget, Messages.privateBlockedNotice) }
            .onFailure { it.rethrowIfCancellation() }
    }

    private suspend fun sendText(target: DeliveryTarget, text: String) {
        TelegramOutputSender.sendText(bot, target.chatId.toChatIdentifier(), text, replyParameters(target.chatId, target.replyToMessageId))
    }

    private suspend fun sendOutgoing(target: DeliveryTarget, item: BotOutput, caption: String?) {
        TelegramOutputSender.send(bot, item, target.chatId.toChatIdentifier(), replyParameters(target.chatId, target.replyToMessageId), caption)
    }

    private fun isPrivateChatBlocked(error: Throwable): Boolean =
        (error as? RequestException)?.isForbidden() == true
}
