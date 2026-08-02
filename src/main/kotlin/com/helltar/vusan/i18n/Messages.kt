package com.helltar.vusan.i18n

interface Messages {

    val startReply: String
    val busyReply: String
    val fallbackErrorReply: String
    val overloadedReply: String
    val formattingAsFileNotice: String
    val privateBlockedNotice: String
    val historyClearedReply: String
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

    fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String

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

    fun taskScheduledByNotice(mention: String): String

    fun taskFollowUpNotice(mention: String): String

    companion object {
        fun of(language: Language): Messages =
            when (language) {
                Language.ENGLISH -> EnglishMessages
                Language.UKRAINIAN -> UkrainianMessages
            }

        fun forCode(code: String?): Messages = of(Language.fromCode(code))
    }
}

internal object EnglishMessages : Messages {

    override val startReply = "Hey! Just tell me what you need 👋"

    override val busyReply = "Hold on, I'm still working on your previous request 😊"

    override val fallbackErrorReply = "Something went wrong — try again? 🥲"

    override val overloadedReply = "I'm a bit overloaded right now — give me a moment and try again 🙏"

    override val formattingAsFileNotice =
        "Telegram couldn't display the formatting, so here's the full reply as a file 📄"

    override val privateBlockedNotice =
        "I tried to DM you, but I can't — please open my chat and press /start first, then ask again 😊"

    override val historyClearedReply =
        "Conversation history cleared. Memory and scheduled tasks are unchanged. 🧹"

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

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "That voice message is ${durationSeconds}s long — I can only transcribe up to ${maxSeconds}s, " +
                "send a shorter one or type it out"

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

    override fun taskScheduledByNotice(mention: String) = "⏰ Scheduled by $mention"

    override fun taskFollowUpNotice(mention: String) = "💬 Following up with $mention"
}

internal object UkrainianMessages : Messages {

    override val startReply = "Привіт! Просто скажи, що тобі треба 👋"

    override val busyReply = "Зачекай, я ще працюю над твоїм попереднім запитом 😊"

    override val fallbackErrorReply = "Щось пішло не так — спробуй ще раз? 🥲"

    override val overloadedReply = "Зараз я трохи перевантажена — дай хвилинку й спробуй ще раз 🙏"

    override val formattingAsFileNotice =
        "Телеграм не зміг показати форматування, тож ось повна відповідь файлом 📄"

    override val privateBlockedNotice =
        "Хотіла написати тобі в особисті, але не виходить — відкрий мій чат, натисни /start, а потім спитай ще раз 😊"

    override val historyClearedReply =
        "Історію переписки очищено. Памʼять і заплановані завдання не змінено. 🧹"

    override val voiceEmptyReply = "Я нічого не розчула у цьому голосовому — спробуй ще раз або напиши текстом 🙉"

    override val voiceTranscriptionFailedReply = "Не вдалося розпізнати це голосове — напиши краще текстом 😊"

    override val inlineChoiceNotOwnerAlert = "Цей вибір був призначений іншому користувачу."

    override val inlineChoiceUnavailableAlert = "Цей вибір уже недоступний."

    override val inlineChoiceErrorAlert = "Не вдалося застосувати вибір — спробуй ще раз."

    override val taskMenuNotOwnerAlert = "Це меню завдань належить іншому користувачу."

    override val taskMenuUnavailableAlert = "Це завдання вже недоступне."

    override val taskMenuPastOnceAlert = "Час цього одноразового завдання вже минув, тому його не можна відновити."

    override val taskMenuErrorAlert = "Не вдалося оновити завдання — спробуй ще раз."

    override val taskMenuRefreshButton = "🔄 Оновити"

    override val taskMenuBackButton = "↩️ Назад"

    override val taskMenuDeleteButton = "🗑 Видалити"

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "Це голосове триває ${durationSeconds}с — я можу розпізнати щонайбільше ${maxSeconds}с, " +
                "надішли коротше або напиши текстом"

    override fun inlineChoiceSelected(option: String) = "✅ Обрано: $option"

    override fun taskMenuTitle(currentChatOnly: Boolean): String =
        if (currentChatOnly)
            "<b>🗓 Твої заплановані завдання у цьому чаті</b>"
        else
            "<b>🗓 Твої заплановані завдання</b>"

    override fun taskMenuCapacity(currentChatOnly: Boolean, listed: Int, total: Int, limit: Int): String =
        if (currentChatOnly)
            "<i>У цьому чаті: $listed\nУ всіх чатах: $total · ліміт: $limit</i>"
        else
            "<i>Завдань: $total · ліміт: $limit</i>"

    override fun taskMenuEmpty(currentChatOnly: Boolean): String =
        if (currentChatOnly)
            "У тебе немає запланованих завдань у цьому чаті."
        else
            "У тебе немає запланованих завдань."

    override fun taskMenuHiddenNotice(hidden: Int) =
        "<i>Ще $hidden сюди не вмістилося — просто спитай про них своїми словами.</i>"

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
            append(if (paused) "⏸ На паузі" else "🟢 Активне")
        }

    override fun taskMenuPauseButton(id: Long) = "⏸ Пауза #$id"

    override fun taskMenuResumeButton(id: Long) = "▶️ Відновити #$id"

    override fun taskMenuCancelButton(id: Long) = "🗑 Скасувати #$id"

    override fun taskMenuDeleteConfirmation(id: Long, label: String): String =
        "<b>Видалити завдання #$id · $label?</b>\n\nЦю дію не можна скасувати."

    override fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { " «$it»" } ?: ""
        return "⏰ Пропустила завдання #$id$label, заплановане на $scheduledFor — я була офлайн."
    }

    override fun taskScheduledByNotice(mention: String) = "⏰ Заплановано: $mention"

    override fun taskFollowUpNotice(mention: String) = "💬 Повертаюся до розмови з $mention"
}
