package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.delivery.chatTarget
import com.helltar.vusan.telegram.delivery.ephemeralReplyParameters
import com.helltar.vusan.telegram.delivery.replyParameters
import com.helltar.vusan.telegram.inbound.BotCommand
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.isEphemeral
import com.helltar.vusan.telegram.inbound.isPrivateChat
import com.helltar.vusan.telegram.inbound.language
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.normalizeUsername
import com.helltar.vusan.telegram.inbound.senderIdOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.objects.ephemeral.EphemeralMessageParameters
import org.telegram.telegrambots.meta.api.objects.message.Message

/**
 * The slash commands that take a direct path and never enter the agent loop: `/start`, `/tasks`, `/clear`
 * and `/stop`. The runner hands over a message it has already accepted — allowlisted, addressed to the
 * bot and claimed for its one turn — so this only does what the command says.
 *
 * This is the source of truth for which commands exist: the menu [publishCommandMenu] writes and the
 * `Telegram commands` section of the system prompt follow it.
 */
internal class TelegramCommands(
    private val delivery: TelegramDelivery,
    private val agent: AgentRunner,
    private val taskMenu: TaskMenuHandler,
) {

    /** Whether [command] is one of these and meant for this bot: `/stop@other_bot` in a group is not. */
    fun recognizes(command: BotCommand, profile: BotProfile): Boolean =
        command.command in NAMES &&
                (command.targetUsername == null || normalizeUsername(command.targetUsername) == normalizeUsername(profile.username))

    suspend fun handle(command: BotCommand, message: Message) {
        when (command.command) {
            START_COMMAND -> delivery.sendReply(message, Messages.of(message.language).startReply)
            TASKS_COMMAND -> sendTaskMenu(message)
            CLEAR_COMMAND -> clear(message)
            STOP_COMMAND -> stop(message)
        }
    }

    private suspend fun sendTaskMenu(message: Message) {
        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping /tasks without sender user (chat=${message.chatIdLong})" }
                return
            }

        val messages = Messages.of(message.language)

        // in a group the menu is one person's business, so it is shown to them alone. a client that knows
        // the command is ephemeral sends it so, typed or picked; one that does not (an old app, a stale
        // command list) sends a plain message, which only an administrator may answer for one person's
        // eyes, so the open menu is what it was before
        val forSenderOnly = !message.isPrivateChat

        // the menu is sent straight to the bot api, so unlike an agent reply it has no delivery
        // fallback chain — without this the user would see nothing at all when the send is rejected.
        runCatching {
            taskMenu.sendMenu(
                target = message.chatTarget,
                userId = userId,
                replyParameters = if (forSenderOnly) message.ephemeralMessageId?.let(::ephemeralReplyParameters) else replyParameters(message.messageIdLong),
                chatIsPrivate = message.isPrivateChat,
                messages = messages,
                ephemeral = EphemeralMessageParameters.builder().receiverUserId(userId).build().takeIf { forSenderOnly },
            )
        }.recoverCatching { error ->
            error.rethrowIfCancellation()
            if (!forSenderOnly || message.isEphemeral) throw error
            log.info { "no ephemeral task menu in chat=${message.chatIdLong} for user=$userId: ${error.message}" }

            taskMenu.sendMenu(
                target = message.chatTarget,
                userId = userId,
                replyParameters = replyParameters(message.messageIdLong),
                chatIsPrivate = message.isPrivateChat,
                messages = messages,
            )
        }.onFailure { error ->
            error.rethrowIfCancellation()
            log.error(error) { "failed to send task menu for chat=${message.chatIdLong} user=$userId" }
            delivery.sendReply(message, messages.fallbackErrorReply)
        }
    }

    // it arrives on its own coroutine, so the turn it stops holding the conversation lock is no obstacle:
    // that is the whole point — every other path into the agent would answer `busyReply` and wait.
    private suspend fun stop(message: Message) {
        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping /stop without sender user (chat=${message.chatIdLong})" }
                return
            }

        // the stopped turn reports where it was working; this only answers when there was nothing to stop.
        if (agent.stop(message.conversationScopeOf(userId))) {
            log.info { "stopped the running turn: chat=${message.chatIdLong} user=$userId" }
            return
        }

        delivery.sendReply(message, Messages.of(message.language).nothingToStopReply)
    }

    private suspend fun clear(message: Message) {
        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping /clear without sender user (chat=${message.chatIdLong})" }
                return
            }

        agent.clearConversation(message.conversationScopeOf(userId))

        // from a group's menu the command reached the bot alone, and so does the answer
        val reply = Messages.of(message.language).conversationClearedReply
        if (message.isEphemeral) delivery.sendForSenderOnly(message, reply) else delivery.sendReply(message, reply)
    }

    private companion object {
        const val START_COMMAND = "start"
        val NAMES = setOf(START_COMMAND, TASKS_COMMAND, CLEAR_COMMAND, STOP_COMMAND)

        val log = KotlinLogging.logger {}
    }
}
