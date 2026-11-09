package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.delivery.chatTarget
import com.helltar.vusan.telegram.delivery.ephemeralReplyParameters
import com.helltar.vusan.telegram.delivery.replyParameters
import com.helltar.vusan.telegram.delivery.sendEphemeralWithAnchorFallback
import com.helltar.vusan.telegram.inbound.BotCommand
import com.helltar.vusan.telegram.inbound.chatIdLong
import com.helltar.vusan.telegram.inbound.isEphemeral
import com.helltar.vusan.telegram.inbound.isPerson
import com.helltar.vusan.telegram.inbound.isPrivateChat
import com.helltar.vusan.telegram.inbound.language
import com.helltar.vusan.telegram.inbound.messageIdLong
import com.helltar.vusan.telegram.inbound.normalizeUsername
import com.helltar.vusan.telegram.inbound.normalizedUsernames
import com.helltar.vusan.telegram.inbound.senderIdOrNull
import com.helltar.vusan.tools.sandbox.SandboxClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import org.telegram.telegrambots.meta.api.objects.ephemeral.EphemeralMessageParameters
import org.telegram.telegrambots.meta.api.objects.message.Message

/**
 * The slash commands that skip the agent loop: `/start`, `/tasks`, `/clear`, `/stop` and, where a sandbox
 * server is configured, `/sandbox`. The runner hands over a message already accepted (allowlisted, addressed,
 * claimed), so this only does what the command says.
 *
 * The source of truth for which commands exist: the menu [publishCommandMenu] writes and the system prompt's
 * `Telegram commands` section follow it.
 */
internal class TelegramCommands(
    private val delivery: TelegramDelivery,
    private val agent: AgentRunner,
    private val taskMenu: TaskMenuHandler,
    // null where no sandbox server is configured: `/sandbox` is then no command at all, and reaches the agent
    // as any other text
    private val sandbox: SandboxClient? = null,
) {

    private val names = NAMES + listOfNotNull(SANDBOX_COMMAND.takeIf { sandbox != null })

    /** Whether [command] is one of these and for this bot: `/stop@other_bot` in a group is not. */
    fun recognizes(command: BotCommand, profile: BotProfile): Boolean =
        command.command in names &&
                (command.targetUsername == null || normalizeUsername(command.targetUsername) in profile.usernames.normalizedUsernames())

    suspend fun handle(command: BotCommand, message: Message) {
        // an ephemeral command must be answered within 15 seconds, so its lateness tells a refused answer
        // from a broken one
        log.info {
            "command: name=[${command.command}] chat=[${message.chatIdLong}] user=[${message.senderIdOrNull()}] " +
                    "ephemeral=[${message.isEphemeral}] age=[${message.date?.let { Instant.now().epochSecond - it }}s]"
        }

        when (command.command) {
            START_COMMAND -> delivery.sendReply(message, Messages.of(message.language).startReply)
            TASKS_COMMAND -> sendTaskMenu(message)
            CLEAR_COMMAND -> clear(message)
            STOP_COMMAND -> stop(message)
            SANDBOX_COMMAND -> sandbox?.let { sendSandboxOverview(it, message) }
        }
    }

    private suspend fun sendTaskMenu(message: Message) {
        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping /tasks without sender user (chat=${message.chatIdLong})" }
                return
            }

        val messages = Messages.of(message.language)

        // in a group the menu is one person's business, so it is shown to them alone. a client that knows the
        // command is ephemeral sends it so; an older one sends a plain message, which only an administrator
        // may answer privately, so the menu goes out in the open as before
        val forSenderOnly = !message.isPrivateChat

        // the menu goes straight to the bot api with no delivery fallback chain; without this a rejected send
        // would show the user nothing.
        runCatching {
            if (forSenderOnly) {
                sendEphemeralWithAnchorFallback(message.ephemeralMessageId?.let(::ephemeralReplyParameters)) { anchor ->
                    taskMenu.sendMenu(
                        target = message.chatTarget,
                        userId = userId,
                        replyParameters = anchor,
                        chatIsPrivate = false,
                        messages = messages,
                        ephemeral = EphemeralMessageParameters.builder().receiverUserId(userId).build(),
                    )
                }
            } else {
                taskMenu.sendMenu(
                    target = message.chatTarget,
                    userId = userId,
                    replyParameters = replyParameters(message.messageIdLong),
                    chatIsPrivate = message.isPrivateChat,
                    messages = messages,
                )
            }
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

            // nobody else saw an ephemeral command, so an apology in the open would answer nothing they know
            // of, and its sender is out of reach
            if (message.isEphemeral) {
                log.warn { "no task menu for an ephemeral /tasks in chat=${message.chatIdLong} user=$userId: ${error.message}" }
                return
            }

            log.error(error) { "failed to send task menu for chat=${message.chatIdLong} user=$userId" }
            delivery.sendReply(message, messages.fallbackErrorReply)
        }
    }

    // it arrives on its own coroutine, so the turn it stops holding the conversation lock is no obstacle; any
    // other path into the agent would answer `busyReply`.
    private suspend fun stop(message: Message) {
        val userId =
            message.senderIdOrNull() ?: run {
                log.warn { "skipping /stop without sender user (chat=${message.chatIdLong})" }
                return
            }

        // the stopped turn reports where it was; this answers only when there was nothing to stop.
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

        if (!message.isEphemeral) {
            delivery.sendReply(message, reply)
            return
        }

        // the history is gone either way; a refused confirmation is only logged
        runCatching { delivery.sendForSenderOnly(message, reply) }
            .onFailure { error ->
                error.rethrowIfCancellation()
                log.warn { "could not confirm an ephemeral /clear in chat=${message.chatIdLong} user=$userId: ${error.message}" }
            }
    }

    private suspend fun sendSandboxOverview(client: SandboxClient, message: Message) {
        val sender =
            message.from ?: run {
                log.warn { "skipping /sandbox without sender user (chat=${message.chatIdLong})" }
                return
            }

        val messages = Messages.of(message.language)

        // a sandbox is filed under one person, which the account anonymous admins and channels share is not
        val text = if (sender.isPerson) client.overviewReply(sender.id, messages) else messages.sandboxNotPersonalReply

        if (message.isPrivateChat) {
            delivery.sendReply(message, text)
            return
        }

        // in a group it is that person's business, as their tasks are: for their eyes where telegram allows
        // it, and in the open for a client that sent the command plain to a bot that is no administrator
        runCatching { delivery.sendForSenderOnly(message, text) }
            .onFailure { error ->
                error.rethrowIfCancellation()

                if (message.isEphemeral) {
                    log.warn { "no answer to an ephemeral /sandbox in chat=${message.chatIdLong} user=${sender.id}: ${error.message}" }
                    return
                }

                log.info { "no ephemeral /sandbox answer in chat=${message.chatIdLong} for user=${sender.id}: ${error.message}" }
                delivery.sendReply(message, text)
            }
    }

    private companion object {
        const val START_COMMAND = "start"
        val NAMES = setOf(START_COMMAND, TASKS_COMMAND, CLEAR_COMMAND, STOP_COMMAND)

        val log = KotlinLogging.logger {}
    }
}
