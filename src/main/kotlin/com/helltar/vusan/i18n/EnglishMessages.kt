package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import kotlin.time.Duration

internal object EnglishMessages : Messages {

    override val startReply = "Hey! Just tell me what you need 👋"

    override val busyReply = "Hold on, I'm still working on your previous request 😊"

    override val fallbackErrorReply = "Something went wrong — try again? 🥲"

    override val overloadedReply = "I'm a bit overloaded right now — give me a moment and try again 🙏"
    override val subscriptionLimitReply = "I've hit my usage limit for now — it resets after a while, try again later 🙏"
    override val signInRequiredReply = "My connection to the AI service needs renewing — my owner has to sign in again 🔑"

    override val formattingAsFileNotice =
        "Telegram couldn't display the formatting, so here's the full reply as a file 📄"

    override val privateBlockedNotice =
        "I tried to DM you, but I can't — please open my chat and press /start first, then ask again 😊"

    override val conversationClearedReply =
        "Our conversation history in this chat is cleared. Other chats, memory and scheduled tasks are unchanged. 🧹"

    override val voiceEmptyReply = "I couldn't hear anything in that voice message — try again or send it as text 🙉"

    override val voiceTranscriptionFailedReply = "I couldn't transcribe that voice message — send it as text instead 😊"

    override val inlineChoiceNotOwnerAlert = "This choice was meant for someone else."

    override val inlineChoiceUnavailableAlert = "This choice is no longer available."

    override val inlineChoiceErrorAlert = "Couldn't apply that choice — try again."

    override val taskMenuNotOwnerAlert = "This task menu belongs to someone else."

    override val taskMenuUnavailableAlert = "That task is no longer available."

    override val taskMenuPastOnceAlert = "This one-time task is already in the past and can't be resumed."

    override val taskMenuErrorAlert = "Couldn't update the task — try again."

    override val taskMenuRefreshButton = "🔄 Refresh"

    override val taskMenuBackButton = "↩️ Back"

    override val taskMenuDeleteButton = "🗑 Delete"

    override val tasksCommandDescription = "Manage scheduled tasks"
    override val clearCommandDescription = "Clear conversation history"

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "That voice message is ${durationSeconds}s long — I can only transcribe up to ${maxSeconds}s, " +
                "send a shorter one or type it out"

    override fun tokenBudgetExhaustedReply(untilReset: Duration): String =
        "Today's token budget is spent — come back in about ${waitLabel(untilReset)} ⏳"

    override fun tokenShareExhaustedReply(untilReset: Duration): String =
        "Today's token budget is running low, and your share of it is used up — " +
                "I'm keeping the rest for everyone else. Come back in about ${waitLabel(untilReset)} ⏳"

    private fun waitLabel(untilReset: Duration): String =
        untilReset.toComponents { hours, minutes, _, _ ->
            when {
                hours > 0 -> "${hours}h ${minutes}min"
                minutes > 0 -> "${minutes}min"
                else -> "a minute"
            }
        }

    override fun inlineChoiceSelected(option: String) = "✅ Selected: $option"

    override fun taskMenuTitle(currentChatOnly: Boolean): String =
        if (currentChatOnly)
            "<b>🗓 Your scheduled tasks in this chat</b>"
        else
            "<b>🗓 Your scheduled tasks</b>"

    override fun taskMenuCapacity(currentChatOnly: Boolean, listed: Int, total: Int, limit: Int): String =
        if (currentChatOnly)
            "<i>In this chat: $listed\nAcross all chats: $total · limit: $limit</i>"
        else
            "<i>Tasks: $total · limit: $limit</i>"

    override fun taskMenuEmpty(currentChatOnly: Boolean): String =
        if (currentChatOnly)
            "You don't have any scheduled tasks in this chat."
        else
            "You don't have any scheduled tasks."

    override fun taskMenuHiddenNotice(hidden: Int) =
        "<i>$hidden more didn't fit here — just ask me about them in your own words.</i>"

    override fun taskMenuItem(
        id: Long,
        label: String,
        nextFire: String,
        recurrence: String,
        paused: Boolean
    ): String =
        buildString {
            append("<b>#$id · $label</b>\n")
            append("🕒 $nextFire\n")
            append("🔁 $recurrence\n")
            append(if (paused) "⏸ Paused" else "🟢 Active")
        }

    override fun taskMenuPauseButton(id: Long) = "⏸ Pause #$id"

    override fun taskMenuResumeButton(id: Long) = "▶️ Resume #$id"

    override fun taskMenuCancelButton(id: Long) = "🗑 Cancel #$id"

    override fun taskMenuDeleteConfirmation(id: Long, label: String): String =
        "<b>Delete task #$id · $label?</b>\n\nThis can't be undone."

    override fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { " «$it»" } ?: ""
        return "⏰ Skipped task #$id$label scheduled for $scheduledFor — I was offline."
    }

    override fun taskFailedNotice(id: Long, title: String?): String {
        val label = title?.let { " «$it»" } ?: ""
        return "⚠️ Task #$id$label went nowhere — I couldn't finish it, even after a few tries."
    }

    override fun taskScheduledByNotice(mention: String) = "⏰ Scheduled by $mention"

    override fun taskFollowUpNotice(mention: String) = "💬 Following up with $mention"

    override fun progressLabel(activity: ToolActivity): String =
        when (activity) {
            ToolActivity.WRITING -> "Writing a reply"
            ToolActivity.SEARCHING_WEB -> "Searching the web"
            ToolActivity.READING_PAGE -> "Reading the page"
            ToolActivity.READING_CHANNEL -> "Reading the channel"
            ToolActivity.READING_TRANSCRIPT -> "Reading the video transcript"
            ToolActivity.READING_CHAT_LOG -> "Reading the chat history"
            ToolActivity.SEARCHING_IMAGES -> "Looking for pictures"
            ToolActivity.SEARCHING_GIF -> "Looking for a GIF"
            ToolActivity.DRAWING -> "Drawing"
            ToolActivity.RUNNING_CODE -> "Running code"
            ToolActivity.LOOKING_AT_IMAGE -> "Looking at the image"
            ToolActivity.WATCHING_VIDEO -> "Watching the video"
            ToolActivity.DOWNLOADING_VIDEO -> "Downloading the video"
            ToolActivity.DOWNLOADING_AUDIO -> "Getting the audio"
            ToolActivity.SENDING_FILE -> "Preparing the file"
            ToolActivity.SPEAKING -> "Recording a voice message"
            ToolActivity.REMEMBERING -> "Updating what I remember"
            ToolActivity.MANAGING_TASKS -> "Updating your scheduled tasks"
        }
}
