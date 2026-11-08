package com.helltar.vusan.telegram

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.llm.FallbackInUse
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

// telegram clears a chat action after ~5s, so re-assert it just under that.
private val ACTION_REFRESH = 4.seconds

// how long a job the user waits on runs before it earns a message; a bubble deleted a second after it appears
// is worse than none.
private val JOB_GRACE = 2.seconds

// and everything else. looking at a photo, writing the reply, saving a memory are conversation, not work to
// watch: a status bubble with a stop button reads as a progress UI, and the chat action already says it as
// typing does. a turn that really drags still earns its message.
private val CONVERSATION_GRACE = 30.seconds

internal fun statusGraceFor(activity: ToolActivity): Duration =
    when (activity) {
        ToolActivity.SEARCHING_WEB,
        ToolActivity.DRAWING,
        ToolActivity.FILMING,
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
 * Shows what the turn is doing while [block] runs. [block] gets a setter for the agent, so the indicator
 * follows the running tool, and the turn's [TurnStatus], which tools narrate a plan into.
 *
 * Two surfaces: a chat action from the start, which costs nothing, then the status message once the turn
 * earns one, when the action stands down so the turn is not announced twice. [statusGraceFor] decides: a job
 * the user waits on opens it in seconds, a conversational activity only if the turn drags well past it. An
 * announced plan opens it at once, being deliberate rather than a side effect of a slow tool, and an open
 * message then follows every activity. The status is closed here, not by the caller, so a turn stopped by
 * cancellation still removes its bubble. A turn that [acknowledge]s marks its message received first, for the
 * while neither surface shows anything: see [TurnStatus.acknowledge].
 */
internal suspend fun <T> TelegramClient.withLiveProgress(
    request: AgentRequest,
    fallbackInUse: () -> FallbackInUse? = { null },
    onPosted: suspend (text: String) -> Unit = {},
    acknowledge: Boolean = false,
    icons: InterfaceIcons = InterfaceIcons(),
    block: suspend (setActivity: (ToolActivity?) -> Unit, status: TurnStatus) -> T,
): T =
    coroutineScope {
        val activity = MutableStateFlow<ToolActivity?>(null)
        val status = statusFor(request, fallbackInUse, onPosted, acknowledge, icons)
        status.acknowledge()
        val started = TimeSource.Monotonic.markNow()

        val actionTicker =
            launch {
                activity.collectLatest { current ->
                    // collectLatest cancels this block's own child job on a new activity, which the outer
                    // isActive would not reflect.
                    while (currentCoroutineContext().isActive) {
                        runCatching {
                            indicateChatAction(
                                request.context.chatTarget,
                                chatActionFor(current),
                            )
                        }
                            .onFailure { it.rethrowIfCancellation() }

                        delay(ACTION_REFRESH)
                    }
                }
            }

        val statusTicker =
            launch {
                // a null activity means nothing worth naming runs; it never takes a live status down, since
                // the last thing named is still true.
                activity.filterNotNull().collectLatest { current ->
                    // once a bubble is up, naming the next step is the edit it would make anyway.
                    if (status.showActivity(current, mayOpen = false)) actionTicker.cancel()

                    // the grace counts from the turn's start, not this activity's: a run of quick searches is
                    // a long turn though none lasts. collectLatest drops the wait when the activity changes,
                    // so what opens the message is always what is still running.
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
            // the turn may be leaving through `/stop`, and the bubble must close either way.
            withContext(NonCancellable) { status.finish() }
        }
    }

// the live status and typing indicator go to the turn's own chat and topic.
private val RequestContext.chatTarget: ChatTarget
    get() = ChatTarget(chatRef.telegramChatId, telegramThreadId(chat.threadId))

private fun TelegramClient.statusFor(
    request: AgentRequest,
    fallbackInUse: () -> FallbackInUse?,
    onPosted: suspend (text: String) -> Unit,
    acknowledge: Boolean,
    icons: InterfaceIcons,
): TurnStatus =
    TurnStatus(
        client = this,
        target = request.context.chatTarget,
        ownerId = request.context.user.telegramUserId,
        replyToMessageId = request.context.messageId?.telegramMessageId,
        messages = Messages.of(request.context.language),
        activityOpensIt = request.context.chat.capabilities.slowModeSeconds == 0,
        acknowledges = acknowledge,
        fallbackInUse = fallbackInUse,
        onPosted = onPosted,
        icons = icons,
    )

private suspend fun TelegramClient.indicateChatAction(target: ChatTarget, action: ActionType) {
    api {
        executeAsync(
            SendChatAction.builder()
                .chatId(target.chatId)
                .messageThreadId(target.messageThreadId)
                .action(action.toString())
                .build(),
        )
    }
}
