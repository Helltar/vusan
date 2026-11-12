package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import kotlin.time.Duration

interface Messages {

    val startReply: String
    val busyReply: String
    val fallbackErrorReply: String
    val overloadedReply: String
    val signInRequiredReply: String
    val contentPolicyReply: String
    val formattingAsFileNotice: String
    val privateBlockedNotice: String
    val conversationClearedReply: String
    val turnStoppedNotice: String
    val nothingToStopReply: String

    // a button's text is words alone, here and in the task menu: the icon before them is the same in every
    // language, and the adapter draws it
    val turnStopButton: String
    val turnStopNotOwnerAlert: String
    val voiceEmptyReply: String
    val voiceTranscriptionFailedReply: String
    val voiceUnsupportedReply: String
    val inlineChoiceNotOwnerAlert: String
    val inlineChoiceUnavailableAlert: String
    val inlineChoiceErrorAlert: String
    val taskMenuNotOwnerAlert: String
    val taskMenuUnavailableAlert: String
    val taskMenuPastOnceAlert: String
    val taskMenuErrorAlert: String
    val taskMenuRefreshButton: String
    val taskMenuBackButton: String
    val taskMenuDeleteButton: String

    /** Command menu entries, as telegram lists them beside the input field. */
    val tasksCommandDescription: String
    val clearCommandDescription: String
    val stopCommandDescription: String
    val sandboxCommandDescription: String

    /** The `/sandbox` answers that are one sentence: nothing to show, nobody to show it to, no answer. */
    val sandboxNoneReply: String
    val sandboxNotPersonalReply: String
    val sandboxUnavailableReply: String
    val sandboxTitle: String

    /**
     * A reply to one of the bot's ephemeral answers, visible to the two of them only: the bot answers in the
     * open.
     */
    val ephemeralChatReply: String

    val taskRecurrenceOnce: String

    fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String

    /**
     * The provider's usage limit is spent; [untilReset] is `null` when the body did not say when it lifts.
     */
    fun subscriptionLimitReply(untilReset: Duration?): String

    fun inlineChoiceSelected(option: String): String

    fun taskMenuTitle(currentChatOnly: Boolean): String

    fun taskMenuCapacity(currentChatOnly: Boolean, listed: Int, total: Int, limit: Int): String

    fun taskMenuEmpty(currentChatOnly: Boolean): String

    fun taskMenuHiddenNotice(hidden: Int): String

    /**
     * The lines of the `/sandbox` answer, one fact each. A date is a day as the server keeps it, and what is
     * left of a term is whole days. Counts follow a colon, which reads right for any number in every language.
     */
    fun sandboxState(running: Boolean, commandsRunning: Int): String

    fun sandboxFilesKept(until: String, daysLeft: Long): String

    fun sandboxFilesReleased(on: String): String

    fun sandboxSnapshots(count: Int, limit: Int?): String

    /** [url] arrives escaped for HTML; the line is sent as HTML. */
    fun sandboxSite(url: String, files: Int, size: String, until: String, daysLeft: Long): String

    fun taskRecurrenceEvery(interval: String): String

    fun taskRecurrenceCron(expression: String): String

    fun taskMenuItem(
        id: Long,
        label: String,
        nextFire: String,
        recurrence: String,
        paused: Boolean,
        deliverPrivately: Boolean,
    ): String

    fun taskMenuPauseButton(id: Long): String

    fun taskMenuResumeButton(id: Long): String

    fun taskMenuCancelButton(id: Long): String

    fun taskMenuDeleteConfirmation(id: Long, label: String): String

    fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String

    fun taskFailedNotice(id: Long, title: String?): String

    /** For the chat a task lives in, when what it sends privately has nowhere to go; the task is paused. */
    fun taskPrivateChatClosedNotice(id: Long, title: String?): String

    fun taskScheduledByNotice(mention: String): String

    fun taskFollowUpNotice(mention: String): String

    /**
     * The live status line while [activity] runs: present tense, no trailing punctuation, since the line adds
     * its own ellipsis.
     */
    fun progressLabel(activity: ToolActivity): String

    /** The line over a finished turn's steps: how long the whole turn took. */
    fun progressDone(elapsed: Duration): String

    /**
     * The status note while [model] stands in for the usual provider, with how long until it is back when the
     * provider said so. On its own line, since a model id wraps a phone-width bubble. HTML: the id is escaped
     * in `<code>` (telegram copies it on tap) and the wait is bold.
     *
     * It opens with `↳` rather than an emoji: it sits under the running-tool line, which has one, and
     * telegram draws emoji larger, so two on consecutive lines crowd each other. An arrow is plain text and
     * reads as a continuation.
     */
    fun fallbackModelNote(model: String, primaryBackIn: Duration?): String

    companion object {
        fun of(language: Language): Messages =
            when (language) {
                Language.ENGLISH -> EnglishMessages
                Language.UKRAINIAN -> UkrainianMessages
                Language.RUSSIAN -> RussianMessages
                Language.SPANISH -> SpanishMessages
            }

        fun forCode(code: String?): Messages = of(Language.fromCode(code))
    }
}
