package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import kotlin.time.Duration

interface Messages {

    val startReply: String
    val busyReply: String
    val fallbackErrorReply: String
    val overloadedReply: String
    val subscriptionLimitReply: String
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

    fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String

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
            }

        fun forCode(code: String?): Messages = of(Language.fromCode(code))
    }
}

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

internal object UkrainianMessages : Messages {

    override val startReply = "Привіт! Просто скажи, що тобі треба 👋"

    override val busyReply = "Зачекай, я ще працюю над твоїм попереднім запитом 😊"

    override val fallbackErrorReply = "Щось пішло не так — спробуй ще раз? 🥲"

    override val overloadedReply = "Зараз у мене забагато запитів — дай хвилинку й спробуй ще раз 🙏"
    override val subscriptionLimitReply = "Я вичерпав ліміт використання — він скоро оновиться, спробуй пізніше 🙏"
    override val signInRequiredReply = "Моє підключення до AI-сервісу треба поновити — власнику потрібно зайти знову 🔑"

    override val formattingAsFileNotice =
        "Телеграм не зміг показати форматування, тож ось повна відповідь файлом 📄"

    override val privateBlockedNotice =
        "Хочу написати тобі в особисті, але не виходить — відкрий мій чат, натисни /start, а потім спитай ще раз 😊"

    override val conversationClearedReply =
        "Історію нашої переписки в цьому чаті очищено. Інші чати, памʼять і заплановані завдання не змінено. 🧹"

    override val voiceEmptyReply = "Не чую нічого в цьому голосовому — спробуй ще раз або напиши текстом 🙉"

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

    override fun tokenBudgetExhaustedReply(untilReset: Duration): String =
        "Бюджет токенів на сьогодні вичерпано — повертайся приблизно за ${waitLabel(untilReset)} ⏳"

    override fun tokenShareExhaustedReply(untilReset: Duration): String =
        "Бюджет токенів на сьогодні добігає кінця, і твоя частка вже вичерпана — " +
                "решту тримаю для інших. Повертайся приблизно за ${waitLabel(untilReset)} ⏳"

    private fun waitLabel(untilReset: Duration): String =
        untilReset.toComponents { hours, minutes, _, _ ->
            when {
                hours > 0 -> "$hours год $minutes хв"
                minutes > 0 -> "$minutes хв"
                else -> "хвилинку"
            }
        }

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
        return "⏰ Завдання #$id$label, заплановане на $scheduledFor, пропущено — мене не було онлайн."
    }

    override fun taskFailedNotice(id: Long, title: String?): String {
        val label = title?.let { " «$it»" } ?: ""
        return "⚠️ Завдання #$id$label не вийшло — не вдалося виконати його навіть за кілька спроб."
    }

    override fun taskScheduledByNotice(mention: String) = "⏰ Заплановано: $mention"

    override fun taskFollowUpNotice(mention: String) = "💬 Повертаюся до розмови з $mention"

    override fun progressLabel(activity: ToolActivity): String =
        when (activity) {
            ToolActivity.WRITING -> "Пишу відповідь"
            ToolActivity.SEARCHING_WEB -> "Шукаю в інтернеті"
            ToolActivity.READING_PAGE -> "Читаю сторінку"
            ToolActivity.READING_CHANNEL -> "Читаю канал"
            ToolActivity.READING_TRANSCRIPT -> "Читаю субтитри до відео"
            ToolActivity.READING_CHAT_LOG -> "Читаю історію чату"
            ToolActivity.SEARCHING_IMAGES -> "Шукаю картинки"
            ToolActivity.SEARCHING_GIF -> "Шукаю GIF"
            ToolActivity.DRAWING -> "Малюю"
            ToolActivity.RUNNING_CODE -> "Виконую код"
            ToolActivity.LOOKING_AT_IMAGE -> "Дивлюся на зображення"
            ToolActivity.WATCHING_VIDEO -> "Дивлюся відео"
            ToolActivity.DOWNLOADING_VIDEO -> "Завантажую відео"
            ToolActivity.DOWNLOADING_AUDIO -> "Дістаю аудіо"
            ToolActivity.SENDING_FILE -> "Готую файл"
            ToolActivity.SPEAKING -> "Записую голосове"
            ToolActivity.REMEMBERING -> "Оновлюю памʼять"
            ToolActivity.MANAGING_TASKS -> "Оновлюю заплановані завдання"
        }
}
