package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.agent.TurnNarrator
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.callback.turnStopCallbackData
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.deleteChatMessage
import com.helltar.vusan.telegram.delivery.editTextMessage
import com.helltar.vusan.telegram.delivery.isEntityParseError
import com.helltar.vusan.telegram.delivery.isMessageGone
import com.helltar.vusan.telegram.delivery.isMessageNotModified
import com.helltar.vusan.telegram.delivery.isReplyMessageNotFound
import com.helltar.vusan.telegram.delivery.replyParameters
import com.helltar.vusan.telegram.delivery.sendStatusMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * The status message's body: the plan the model announced, and under it the line naming what is running.
 * The line is plain text — the emoji already sets it apart from the plan above it — and carries its own
 * ellipsis, since nothing animates one after a message is sent.
 */
internal fun statusMessageText(plan: String?, label: String?): String? {
    val running = label?.let { "$it…" }

    return when {
        plan == null -> running
        running == null -> plan
        else -> "$plan\n\n$running"
    }
}

/**
 * The words for the tool that is running, behind an emoji saying the same thing at a glance. The picture
 * is the same in every language, so it lives here rather than repeated in each [Messages] implementation.
 */
internal fun activityStatusLabel(activity: ToolActivity, messages: Messages): String {
    val emoji =
        when (activity) {
            ToolActivity.WRITING -> "✍️"
            ToolActivity.SEARCHING_WEB -> "🌐"
            ToolActivity.READING_PAGE -> "📄"
            ToolActivity.READING_CHANNEL -> "📢"
            ToolActivity.READING_TRANSCRIPT -> "📝"
            ToolActivity.READING_CHAT_LOG -> "📜"
            ToolActivity.SEARCHING_IMAGES -> "🖼️"
            ToolActivity.SEARCHING_GIF -> "🎞️"
            ToolActivity.DRAWING -> "🎨"
            ToolActivity.RUNNING_CODE -> "💻"
            ToolActivity.LOOKING_AT_IMAGE -> "👀"
            ToolActivity.WATCHING_VIDEO -> "📺"
            ToolActivity.DOWNLOADING_VIDEO -> "🎬"
            ToolActivity.DOWNLOADING_AUDIO -> "🎵"
            ToolActivity.SENDING_FILE -> "📎"
            ToolActivity.SPEAKING -> "🎙️"
            ToolActivity.REMEMBERING -> "🧠"
            ToolActivity.MANAGING_TASKS -> "⏰"
        }

    return "$emoji ${messages.progressLabel(activity)}"
}

/**
 * What a turn shows while it runs: one silent message naming the tool currently working, carrying the
 * plan the model announces before starting long work, with a stop button on it. The same message in
 * every kind of chat — Telegram's own draft surface (`sendMessageDraft`) is private chats only, so it
 * could never be half of this.
 *
 * It opens lazily, on the first named activity or the first thing the model says, and unlike a draft or
 * a chat action it does not expire, so it is written only when something actually changes. [finish]
 * removes it, unless the model put its own words in it: those stay in the chat as the message they
 * already were, without the status line under them and without the button.
 */
internal class TurnStatus(
    private val client: TelegramClient,
    private val target: ChatTarget,
    private val ownerId: Long,
    replyToMessageId: Long?,
    private val messages: Messages,
    // in a slow-mode group the bot's messages are rationed, so a bubble is spent only on words the model
    // chose to send; naming a running tool is never worth one of those slots.
    private val activityOpensIt: Boolean
) : TurnNarrator {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    // a turn writes from two places — the tool that narrates, and the collector following the activity —
    // and both read the state they are about to change.
    private val edits = Mutex()

    // an inline choice starts a turn with no message behind it, and Telegram takes no reply to id 0.
    private var anchor: Long? = replyToMessageId?.takeIf { it > 0 }
    private var messageId: Int? = null
    private var announcement: String? = null
    private var label: String? = null
    private var shown: String? = null
    private var parseMode: String? = ParseMode.HTML
    private var gone = false

    /**
     * Names [activity] in the status, spending a message on it only when [mayOpen]: an activity that is
     * part of the exchange rather than a job fills a bubble already on screen but never opens one of its
     * own. Returns `true` while the bubble is up, which is what stands the chat action down.
     */
    suspend fun showActivity(activity: ToolActivity, mayOpen: Boolean): Boolean =
        edits.withLock {
            label = activityStatusLabel(activity, messages)

            if (messageId == null && !(mayOpen && activityOpensIt)) false else render()
        }

    override suspend fun say(text: String): Boolean =
        edits.withLock {
            val previous = announcement
            announcement = text

            // nothing reached the chat, so the caller still owes the user these words: forget them here
            // and let it queue them the ordinary way.
            render().also { delivered -> if (!delivered) announcement = previous }
        }

    suspend fun finish() {
        edits.withLock {
            val id = messageId ?: return
            val plan = announcement

            messageId = null
            gone = true

            runCatching {
                if (plan == null)
                    deleteChatMessage(client, target.chatId, id)
                else
                    editTextMessage(client, target.chatId, id, plan, replyMarkup = null, parseMode = parseMode)
            }.onFailure { error ->
                error.rethrowIfCancellation()

                // a plan that never got a running line under it is already the text this edit writes.
                if (error.isMessageNotModified()) return

                log.warn { "live status could not be closed in chat=${target.chatId}: ${error.message}" }
            }
        }
    }

    private suspend fun render(): Boolean {
        if (gone) return false

        val text = statusText() ?: return false

        if (text == shown) return true

        return runCatching { push(text) }
            .recoverCatching { error ->
                error.rethrowIfCancellation()

                when {
                    // the model writes its plan in HTML like any other message. broken markup costs the
                    // formatting, not the status itself: the same text goes up again as plain text.
                    error.isEntityParseError() -> {
                        parseMode = null
                        push(text)
                    }

                    // whatever it was answering is gone, but the status still has something to say.
                    error.isReplyMessageNotFound() -> {
                        anchor = null
                        push(text)
                    }

                    else -> throw error
                }
            }
            .map { pushed ->
                shown = pushed
                true
            }
            .getOrElse { error ->
                error.rethrowIfCancellation()

                // the message already carries this text; the local copy was simply behind.
                if (error.isMessageNotModified()) {
                    shown = text
                    return true
                }

                log.warn { "live status rejected in chat=${target.chatId}: ${error.message}" }

                // a message somebody deleted is not coming back and every further write would fail the
                // same way. anything else may be transient, so the next change tries again.
                if (error.isMessageGone()) gone = true

                false
            }
    }

    private fun statusText(): String? = statusMessageText(announcement, label)

    // a write in flight when the turn ends must still finish. `/stop`, or simply the turn being over,
    // cancels the collector this runs in, and a send cancelled mid-flight can still have created the
    // message — leaving an id nobody holds and a status bubble nothing will ever take down.
    private suspend fun push(text: String): String =
        withContext(NonCancellable) {
            val id = messageId

            if (id == null) {
                messageId =
                    sendStatusMessage(
                        client = client,
                        target = target,
                        text = text,
                        parseMode = parseMode,
                        replyParameters = replyParameters(anchor),
                        replyMarkup = stopKeyboard()
                    )
            } else {
                editTextMessage(
                    client = client,
                    chatId = target.chatId,
                    messageId = id,
                    text = text,
                    replyMarkup = stopKeyboard(),
                    parseMode = parseMode
                )
            }

            text
        }

    private fun stopKeyboard(): InlineKeyboardMarkup =
        InlineKeyboardMarkup.builder()
            .keyboard(
                listOf(
                    InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                            .text(messages.turnStopButton)
                            .callbackData(turnStopCallbackData(ownerId))
                            .build()
                    )
                )
            )
            .build()
}
