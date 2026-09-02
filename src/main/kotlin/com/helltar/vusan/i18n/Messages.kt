package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import kotlin.time.Duration

interface Messages {

    val startReply: String
    val busyReply: String
    val fallbackErrorReply: String
    val overloadedReply: String
    val signInRequiredReply: String
    val formattingAsFileNotice: String
    val privateBlockedNotice: String
    val conversationClearedReply: String
    val voiceEmptyReply: String
    val voiceTranscriptionFailedReply: String
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

    /** Command menu entries, as Telegram lists them next to the input field. */
    val tasksCommandDescription: String
    val clearCommandDescription: String

    fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String

    /** The provider's usage limit is spent; [untilReset] is `null` when the error body did not say when it lifts. */
    fun subscriptionLimitReply(untilReset: Duration?): String

    fun tokenBudgetExhaustedReply(untilReset: Duration): String

    fun tokenShareExhaustedReply(untilReset: Duration): String

    fun inlineChoiceSelected(option: String): String

    fun taskMenuTitle(currentChatOnly: Boolean): String

    fun taskMenuCapacity(currentChatOnly: Boolean, listed: Int, total: Int, limit: Int): String

    fun taskMenuEmpty(currentChatOnly: Boolean): String

    fun taskMenuHiddenNotice(hidden: Int): String

    fun taskMenuItem(
        id: Long,
        label: String,
        nextFire: String,
        recurrence: String,
        paused: Boolean
    ): String

    fun taskMenuPauseButton(id: Long): String

    fun taskMenuResumeButton(id: Long): String

    fun taskMenuCancelButton(id: Long): String

    fun taskMenuDeleteConfirmation(id: Long, label: String): String

    fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String

    fun taskFailedNotice(id: Long, title: String?): String

    fun taskScheduledByNotice(mention: String): String

    fun taskFollowUpNotice(mention: String): String

    /**
     * What the progress draft says while [activity] runs. Present tense and no trailing punctuation:
     * the client animates its own ellipsis after the text, so a written one shows up twice.
     */
    fun progressLabel(activity: ToolActivity): String

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
