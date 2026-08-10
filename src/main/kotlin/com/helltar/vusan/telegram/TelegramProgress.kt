package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.telegram.delivery.chatActionFor
import com.helltar.vusan.telegram.delivery.withBrTagsAsNewlines
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.send.SendMessageDraft
import org.telegram.telegrambots.meta.generics.TelegramClient

private val log = KotlinLogging.logger {}

// Telegram clears a chat action after ~5s, so re-assert it just under that.
private val ACTION_REFRESH = 4.seconds

// a message draft is a 30-second preview; re-push it with room to spare for a slow round trip.
private val DRAFT_REFRESH = 20.seconds

/**
 * Show what the turn is doing for as long as [block] runs. [block] receives a setter it hands to the
 * agent, so the indicator follows the tool that is currently executing; the ticker re-asserts its state
 * periodically, and [collectLatest] cancels the in-flight wait so a switch shows immediately.
 *
 * One indicator at a time. A private chat gets a message draft — Telegram's own surface for a generating
 * agent — which says in words what a chat action can only mime, and the action stands down once the first
 * draft lands. A draft is a preview of the reply itself rather than a message of its own, so it ends
 * through [handOffProgressDraft] rather than by being cancelled here: stopping the ticker stops the
 * refresh, it does not take the bubble off the screen. That is also why it takes a named activity to open
 * one at all — see [streamProgressDraft].
 */
internal suspend fun <T> TelegramClient.withLiveProgress(
    request: AgentRequest,
    block: suspend ((ToolActivity?) -> Unit) -> T
): T =
    coroutineScope {
        val activity = MutableStateFlow<ToolActivity?>(null)

        val actionTicker =
            launch {
                activity.collectLatest { current ->
                    // collectLatest cancels this block's own child job on a new activity, which the
                    // outer launch's isActive would not reflect.
                    while (currentCoroutineContext().isActive) {
                        runCatching { indicateChatAction(request.chatId, chatActionFor(current)) }
                            .onFailure { it.rethrowIfCancellation() }

                        delay(ACTION_REFRESH)
                    }
                }
            }

        // drafts are private-chat only; a group turn keeps the chat action for the whole turn.
        val draftTicker =
            if (request.messageContext?.isPrivate == true) {
                launch { streamProgressDraft(request, activity, onShown = { actionTicker.cancel() }) }
            } else {
                null
            }

        try {
            block { activity.value = it }
        } finally {
            actionTicker.cancel()
            draftTicker?.cancel()
        }
    }

private suspend fun TelegramClient.streamProgressDraft(
    request: AgentRequest,
    activity: StateFlow<ToolActivity?>,
    onShown: () -> Unit
) {
    val messages = Messages.of(request.language)
    val draftId = draftIdFor(request.messageId)

    // only a tool with something to say opens a draft, and a null activity never closes it again. a live
    // draft blocks the send button on mobile until it turns into a message, and a turn that answers with a
    // reaction alone — or stays silent — has no message to release it with and no way to withdraw it. Those
    // turns run entirely on activities worth no caption, so gating on one keeps the input free.
    activity.filterNotNull().collectLatest { current ->
        val text = messages.progressLabel(current)

        while (currentCoroutineContext().isActive) {
            runCatching { sendProgressDraft(request.chatId, draftId, text) }
                // the draft says in words what the chat action mimes; leaving both up announces the same
                // turn twice, so the action stands down as soon as a draft actually lands.
                .onSuccess { onShown() }
                .onFailure { error ->
                    error.rethrowIfCancellation()
                    log.warn { "progress draft rejected for chat=${request.chatId}: ${error.message}" }
                }

            delay(DRAFT_REFRESH)
        }
    }
}

/**
 * Hand the finished answer over to the draft, right before it is sent for real. A draft does not
 * disappear on its own — it *becomes* the message whose text starts with the draft's own — so without
 * this the last progress caption would sit beside the reply until it expires (Telegram Desktop shows
 * that; Android does not). Only a plain text reply can be handed over; media, voice and rich messages
 * leave the caption to expire.
 */
internal suspend fun TelegramClient.handOffProgressDraft(request: AgentRequest, result: AgentResult) {
    if (request.messageContext?.isPrivate != true) return

    val text = draftHandoffText(result)?.withBrTagsAsNewlines() ?: return

    runCatching { sendProgressDraft(request.chatId, draftIdFor(request.messageId), text, parseMode = ParseMode.HTML) }
        .onFailure { error ->
            error.rethrowIfCancellation()
            log.warn { "progress draft handoff rejected for chat=${request.chatId}: ${error.message}" }
        }
}

// the first thing delivery will put in the chat, when that is a plain text message: an answer the model
// wrote with `sendMessage`, or its own closing remark when no output tool queued anything.
internal fun draftHandoffText(result: AgentResult): String? =
    when (val first = result.outputs.firstOrNull()?.output) {
        null -> result.comment?.takeUnless { it.isBlank() }
        is BotOutput.Text -> first.text
        else -> null
    }

private suspend fun TelegramClient.sendProgressDraft(
    chatId: Long,
    draftId: Int,
    text: String,
    parseMode: String? = null
) {
    api {
        executeAsync(
            SendMessageDraft.builder()
                .chatId(chatId)
                .draftId(draftId)
                .text(text)
                .parseMode(parseMode)
                .build()
        )
    }
}

private suspend fun TelegramClient.indicateChatAction(chatId: Long, action: ActionType) {
    api {
        executeAsync(SendChatAction.builder().chatId(chatId).action(action.toString()).build())
    }
}

// a draft id is an Int that Telegram rejects when zero, and updates under the same id animate into each
// other. deriving it from the triggering message keeps one turn on one draft; a turn with no message
// behind it (an inline choice) falls back to a fixed id, which is safe because the previous draft of
// that chat has long expired by then.
internal fun draftIdFor(messageId: Long): Int =
    (messageId % Int.MAX_VALUE).toInt().takeIf { it != 0 } ?: 1
