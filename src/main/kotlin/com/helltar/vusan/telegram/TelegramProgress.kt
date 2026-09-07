package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.delivery.chatActionFor
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.generics.TelegramClient

// Telegram clears a chat action after ~5s, so re-assert it just under that.
private val ACTION_REFRESH = 4.seconds

/**
 * Show what the turn is doing for as long as [block] runs. [block] receives a setter it hands to the
 * agent, so the indicator follows the tool that is currently executing, and the turn's [TurnStatus],
 * which the tools narrate a plan into.
 *
 * Two surfaces, in this order: a chat action from the first moment, since it costs nothing and needs no
 * decision, and then the status message once there is something to name — at which point the action
 * stands down, because leaving both up announces the same turn twice. The status is closed here rather
 * than by the caller so that a stopped turn, which leaves through the cancellation, still takes its
 * bubble off the screen.
 */
internal suspend fun <T> TelegramClient.withLiveProgress(
    request: AgentRequest,
    block: suspend (setActivity: (ToolActivity?) -> Unit, status: TurnStatus) -> T
): T =
    coroutineScope {
        val activity = MutableStateFlow<ToolActivity?>(null)
        val status = statusFor(request)

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

        val statusTicker =
            launch {
                // a null activity means nothing worth naming is running; it never takes a live status
                // back down, since the turn is still going and the last thing named is still the truth.
                activity.filterNotNull().collect { current ->
                    if (status.showActivity(current)) actionTicker.cancel()
                }
            }

        try {
            block({ activity.value = it }, status)
        } finally {
            actionTicker.cancel()
            statusTicker.cancel()
            // the turn may be on its way out through `/stop`, and the bubble has to close either way.
            withContext(NonCancellable) { status.finish() }
        }
    }

private fun TelegramClient.statusFor(request: AgentRequest): TurnStatus =
    TurnStatus(
        client = this,
        chatId = request.chatId,
        ownerId = request.userId,
        replyToMessageId = request.messageId,
        messages = Messages.of(request.language),
        activityOpensIt = (request.messageContext?.chatCapabilities?.slowModeSeconds ?: 0) == 0
    )

private suspend fun TelegramClient.indicateChatAction(chatId: Long, action: ActionType) {
    api {
        executeAsync(SendChatAction.builder().chatId(chatId).action(action.toString()).build())
    }
}
