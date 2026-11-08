package com.helltar.vusan.telegram

import com.helltar.vusan.telegram.InterfaceIcons.Icon
import com.helltar.vusan.llm.FallbackInUse
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.agent.TurnNarrator
import com.helltar.vusan.common.escapeHtml
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.callback.turnStopCallbackData
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.TelegramOutputSender
import com.helltar.vusan.telegram.delivery.deleteChatMessage
import com.helltar.vusan.telegram.delivery.editTextMessage
import com.helltar.vusan.telegram.delivery.isEntityParseError
import com.helltar.vusan.telegram.delivery.isMessageGone
import com.helltar.vusan.telegram.delivery.isMessageNotModified
import com.helltar.vusan.telegram.delivery.isReplyMessageNotFound
import com.helltar.vusan.telegram.delivery.replyParameters
import com.helltar.vusan.telegram.delivery.sendStatusMessage
import com.helltar.vusan.telegram.delivery.setMessageReaction
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * The status body, block by block with a blank line between: the announced plan, the steps already done, and
 * the running line with the fallback note under it. With markup ([html]) the steps fold into an expandable
 * quote, so a long turn keeps one line of height for them; the fallback note brings its own markup, as the plan
 * does.
 */
internal fun statusMessageText(
    plan: String?,
    steps: List<String>,
    running: String?,
    fallbackNote: String? = null,
    html: Boolean = true,
): String? {
    val done =
        steps.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.let { if (html) "<blockquote expandable>$it</blockquote>" else it }
    val now = listOfNotNull(running, fallbackNote).joinToString("\n").ifEmpty { null }

    return listOfNotNull(plan, done, now).joinToString("\n\n").ifEmpty { null }
}

/**
 * What a turn shows while it runs: one silent message naming the working tool, carrying the plan the model
 * announces before long work, with a stop button. The same in every kind of chat; telegram's
 * `sendMessageDraft` works in private chats only.
 *
 * It opens lazily on the first named activity or the model's first words, and does not expire, so it is
 * written only on change. [finish] removes it unless the model put words in it: those stay as a message,
 * without the status line and button. A message sent mid-turn ([send]) stands on its own: the plan stays
 * above it as such a message, and the running line and button move under it.
 *
 * A turn with nothing to show for a while (queued behind another, or in a slow-mode chat where activities
 * open no bubble) marks the message received with a reaction ([acknowledge]) and removes it at the end; a
 * reaction the model chose lands after.
 */
internal class TurnStatus(
    private val client: TelegramClient,
    private val target: ChatTarget,
    private val ownerId: Long,
    replyToMessageId: Long?,
    private val messages: Messages,
    // slow mode rations the bot's messages, so a bubble is spent only on words the model chose; a running
    // tool never earns one.
    private val activityOpensIt: Boolean,
    // whether to mark the message received with a reaction while there is nothing else to show
    private val acknowledges: Boolean = false,
    // the answering model and when the usual provider said it would be back, so a fallback turn says so in
    // the chat, not only in the log.
    private val fallbackInUse: () -> FallbackInUse? = { null },
    // the model's words, reported the moment they reach the chat.
    private val onPosted: suspend (text: String) -> Unit = {},
    // the activities' icons and the stop button's, and what this status learns about them
    private val icons: InterfaceIcons = InterfaceIcons(),
    // whether the bubble quotes the message it answers: in a group it says which of several conversations it
    // belongs to, in a private chat there is only the one
    private val quotes: Boolean = true,
) : TurnNarrator {

    // written from two places, the narrating tool and the activity collector, each reading the state it
    // changes.
    private val edits = Mutex()

    // an inline choice starts a turn with no message behind it, and telegram takes no reply to id 0.
    private var anchor: Long? = replyToMessageId
    private var messageId: Int? = null
    private var announcement: String? = null
    private var current: ToolActivity? = null

    // what the turn has finished, in the order it first did each, shown folded above the running line
    private val done = mutableListOf<ToolActivity>()
    private val started = TimeSource.Monotonic.markNow()

    // the fallback model any part of the turn ran on, named in the record it leaves
    private var fallbackModel: String? = null
    private var shown: String? = null
    private var parseMode: String? = ParseMode.HTML
    private var gone = false
    private var acknowledged = false

    /** Marks the message received, for a turn with nothing else to show for a while. */
    suspend fun acknowledge() {
        if (!acknowledges) return

        edits.withLock {
            val id = anchor ?: return

            runCatching { setMessageReaction(client, target.chatId, id.toInt(), RECEIPT_REACTION) }
                .onSuccess { acknowledged = true }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    log.info { "the message could not be marked as received in chat=${target.chatId}: ${error.message}" }
                }
        }
    }

    /**
     * Names [activity] in the status, opening a message only when [mayOpen]: an activity that is part of the
     * exchange fills a bubble already on screen but never opens one. Returns `true` while the bubble is up,
     * which stands the chat action down.
     */
    suspend fun showActivity(activity: ToolActivity, mayOpen: Boolean): Boolean =
        edits.withLock {
            current?.takeIf { it != activity && it !in done }?.let { done += it }
            current = activity

            if (messageId == null && !(mayOpen && activityOpensIt)) false else render()
        }

    override suspend fun say(text: String): Boolean {
        val delivered =
            edits.withLock {
                val previous = announcement
                announcement = text

                // nothing reached the chat, so forget the words and let the caller queue them the ordinary
                // way.
                render().also { delivered -> if (!delivered) announcement = previous }
            }

        if (delivered) onPosted(text)

        return delivered
    }

    override suspend fun send(text: String): Boolean {
        val posted = post(text)

        if (posted) onPosted(text)

        return posted
    }

    private suspend fun post(text: String): Boolean =
        edits.withLock {
            val posted =
                runCatching { withContext(NonCancellable) { TelegramOutputSender.sendText(client, target, text, replyParameters(anchor)) } }
                    .recoverCatching { error ->
                        error.rethrowIfCancellation()

                        // the anchor is gone; the message still has something to say
                        if (!error.isReplyMessageNotFound()) throw error

                        anchor = null
                        withContext(NonCancellable) { TelegramOutputSender.sendText(client, target, text, replyParameters = null) }
                    }
                    .onFailure { error ->
                        error.rethrowIfCancellation()
                        log.warn { "an interim message was rejected in chat=${target.chatId}: ${error.message}" }
                    }
                    .isSuccess

            if (!posted) return false

            // the running line belongs under the newest message, the plan above what it planned: the old
            // bubble keeps only the plan, as [finish] would leave it, and a new one under this message carries
            // the running line and the button.
            messageId?.let { id ->
                val record = recordText(withTime = false)

                messageId = null
                shown = null
                announcement = null
                done.clear()

                runCatching {
                    withContext(NonCancellable) {
                        if (record == null)
                            deleteChatMessage(client, target.chatId, id)
                        else
                            editTextMessage(client, target.chatId, id, record, replyMarkup = null, parseMode = parseMode)
                    }
                }.onFailure { error -> error.rethrowIfCancellation() }

                // in slow mode a running line alone never earns a message.
                if (activityOpensIt) render()
            }

            true
        }

    suspend fun finish() {
        edits.withLock {
            if (acknowledged) {
                acknowledged = false

                anchor?.let { id ->
                    runCatching { setMessageReaction(client, target.chatId, id.toInt(), emoji = null) }
                        .onFailure { error -> error.rethrowIfCancellation() }
                }
            }

            val id = messageId ?: return
            val record = recordText(withTime = true)

            messageId = null
            gone = true

            runCatching {
                if (record == null)
                    deleteChatMessage(client, target.chatId, id)
                else
                    editTextMessage(client, target.chatId, id, record, replyMarkup = null, parseMode = parseMode)
            }.onFailure { error ->
                error.rethrowIfCancellation()

                // a plan with no running line under it is already this text.
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
                    // broken HTML in the plan costs the formatting, not the status: the same text goes up as
                    // plain text, its working sign an ellipsis again.
                    error.isEntityParseError() -> {
                        parseMode = null
                        push(statusText() ?: text)
                    }

                    // the anchor is gone, but the status still has something to say.
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

                // the message already says this; the local copy was behind.
                if (error.isMessageNotModified()) {
                    shown = text
                    return true
                }

                log.warn { "live status rejected in chat=${target.chatId}: ${error.message}" }

                // a deleted message is not coming back and every write would fail; anything else may be
                // transient, so the next change retries.
                if (error.isMessageGone()) gone = true

                false
            }
    }

    // the icons are markup, so a status that dropped its markup draws plain emoji
    private fun statusText(): String? {
        val html = parseMode == ParseMode.HTML

        return statusMessageText(
            plan = announcement,
            steps = done.map { icons.done(messages.progressLabel(it), html) },
            running = current?.let { icons.running(it, messages.progressLabel(it), html) },
            fallbackNote =
                fallbackInUse()?.let {
                    fallbackModel = it.model
                    messages.fallbackModelNote(it.model, it.primaryBackIn)
                },
            html = html,
        )
    }

    /**
     * What stays of the bubble once its turn is over or has moved under a newer message: the plan and every step,
     * folded, under how long the turn took ([withTime]) and, when it ran on the fallback, which model answered;
     * the usual model goes unnamed, so a name there is news. A turn that announced no plan leaves nothing: its
     * status was only ever a progress bar.
     */
    private fun recordText(withTime: Boolean): String? {
        val plan = announcement ?: return null
        val html = parseMode == ParseMode.HTML
        val steps = (done + listOfNotNull(current)).distinct().map { icons.done(messages.progressLabel(it), html) }
        val model = (fallbackInUse()?.model ?: fallbackModel)?.let { if (html) " · <code>${it.escapeHtml()}</code>" else " · $it" }
        val took = if (withTime && steps.isNotEmpty()) listOf(messages.progressDone(started.elapsedNow()) + model.orEmpty()) else emptyList()

        return statusMessageText(plan, took + steps, running = null, html = html)
    }

    // a write in flight when the turn ends must finish: `/stop` or the turn ending cancels the collector, and
    // a canceled send may still have created the message, leaving a bubble nothing takes down.
    private suspend fun push(text: String): String =
        withContext(NonCancellable) {
            val id = messageId

            val sent =
                if (id == null) {
                    sendStatusMessage(
                        client = client,
                        target = target,
                        text = text,
                        parseMode = parseMode,
                        replyParameters = replyParameters(anchor.takeIf { quotes }),
                        replyMarkup = stopKeyboard(),
                    ).also { messageId = it.messageId }
                } else {
                    editTextMessage(
                        client = client,
                        chatId = target.chatId,
                        messageId = id,
                        text = text,
                        replyMarkup = stopKeyboard(),
                        parseMode = parseMode,
                    )
                }

            // an icon went up as markup: what telegram kept of it says whether custom emoji go through
            if (sent != null && parseMode == ParseMode.HTML && InterfaceIcons.carriesCustomEmoji(text)) icons.learnFrom(sent)

            text
        }

    private fun stopKeyboard(): InlineKeyboardMarkup =
        InlineKeyboardMarkup.builder()
            .keyboard(
                listOf(
                    InlineKeyboardRow(
                        icons.button(Icon.STOP, messages.turnStopButton, turnStopCallbackData(ownerId)),
                    ),
                ),
            )
            .build()

    private companion object {
        const val RECEIPT_REACTION = "👀"

        val log = KotlinLogging.logger {}
    }
}
