package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.chatActionFor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
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

// how long a job the user waits on runs before it is worth a message of its own. a bubble that appears
// and is deleted a second later is worse than none, so nothing opens one inside this.
private val JOB_GRACE = 2.seconds

// and how long everything else waits. looking at a photo somebody just sent, writing the reply, saving
// a memory are turns in a conversation rather than work to watch: a status bubble with a stop button on
// it answers them as a progress UI, and the chat action already says the same thing the way a person
// typing does. A turn that genuinely drags on still earns its message.
private val CONVERSATION_GRACE = 30.seconds

internal fun statusGraceFor(activity: ToolActivity): Duration =
    when (activity) {
        ToolActivity.SEARCHING_WEB,
        ToolActivity.DRAWING,
        ToolActivity.RUNNING_CODE,
        ToolActivity.WATCHING_VIDEO,
        ToolActivity.DOWNLOADING_VIDEO,
        ToolActivity.DOWNLOADING_AUDIO -> JOB_GRACE

        ToolActivity.WRITING,
        ToolActivity.SEARCHING_IMAGES,
        ToolActivity.SEARCHING_GIF,
        ToolActivity.READING_PAGE,
        ToolActivity.READING_CHANNEL,
        ToolActivity.READING_TRANSCRIPT,
        ToolActivity.READING_CHAT_LOG,
        ToolActivity.LOOKING_AT_IMAGE,
        ToolActivity.SENDING_FILE,
        ToolActivity.SPEAKING,
        ToolActivity.REMEMBERING,
        ToolActivity.MANAGING_TASKS -> CONVERSATION_GRACE
    }

/**
 * Show what the turn is doing for as long as [block] runs. [block] receives a setter it hands to the
 * agent, so the indicator follows the tool that is currently executing, and the turn's [TurnStatus],
 * which the tools narrate a plan into.
 *
 * Two surfaces, in this order: a chat action from the first moment, since it costs nothing and needs no
 * decision, and then the status message once the turn has earned one — at which point the action stands
 * down, because leaving both up announces the same turn twice. What it takes to earn it is
 * [statusGraceFor]: a job the user waits on opens the message in seconds, an activity that is part of
 * the exchange only if the turn drags on well past it. Either way a plan the model announces opens it
 * at once, being a deliberate act rather than a side effect of some tool being slow, and an open
 * message then follows every activity, heavy or not. The status is closed here rather than by the
 * caller so that a stopped turn, which leaves through the cancellation, still takes its bubble off the
 * screen.
 */
internal suspend fun <T> TelegramClient.withLiveProgress(
    request: AgentRequest,
    block: suspend (setActivity: (ToolActivity?) -> Unit, status: TurnStatus) -> T
): T =
    coroutineScope {
        val activity = MutableStateFlow<ToolActivity?>(null)
        val status = statusFor(request)
        val started = TimeSource.Monotonic.markNow()

        val actionTicker =
            launch {
                activity.collectLatest { current ->
                    // collectLatest cancels this block's own child job on a new activity, which the
                    // outer launch's isActive would not reflect.
                    while (currentCoroutineContext().isActive) {
                        runCatching {
                            indicateChatAction(
                                request.context.chatTarget,
                                chatActionFor(current)
                            )
                        }
                            .onFailure { it.rethrowIfCancellation() }

                        delay(ACTION_REFRESH)
                    }
                }
            }

        val statusTicker =
            launch {
                // a null activity means nothing worth naming is running; it never takes a live status
                // back down, since the turn is still going and the last thing named is still the truth.
                activity.filterNotNull().collectLatest { current ->
                    // whatever the turn is doing fills a bubble that is already up: once a message has
                    // been earned, naming the next step is the edit it would make anyway.
                    if (status.showActivity(current, mayOpen = false)) actionTicker.cancel()

                    // the grace is measured from the start of the turn rather than of this activity: a
                    // run of quick searches is a long turn even though no single one of them lasts.
                    // collectLatest drops this wait when the activity changes, so what opens the message
                    // is always the thing still running.
                    val wait = statusGraceFor(current) - started.elapsedNow()

                    if (wait.isPositive()) delay(wait)

                    if (status.showActivity(current, mayOpen = true)) actionTicker.cancel()
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

// where the live status bubble and the typing indicator go: the turn's own chat and topic.
private val RequestContext.chatTarget: ChatTarget
    get() = ChatTarget(chatRef.telegramChatId, telegramThreadId(chat.threadId))

private fun TelegramClient.statusFor(request: AgentRequest): TurnStatus =
    TurnStatus(
        client = this,
        target = request.context.chatTarget,
        ownerId = request.context.user.telegramUserId,
        replyToMessageId = request.context.messageId,
        messages = Messages.of(request.context.language),
        activityOpensIt = request.context.chat.capabilities.slowModeSeconds == 0
    )

private suspend fun TelegramClient.indicateChatAction(target: ChatTarget, action: ActionType) {
    api {
        executeAsync(
            SendChatAction.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .action(action.toString())
                .build()
        )
    }
}
