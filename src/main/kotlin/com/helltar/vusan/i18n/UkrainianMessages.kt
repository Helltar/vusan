package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import kotlin.time.Duration

internal object UkrainianMessages : Messages {

    override val startReply = "Привіт! Просто скажи, що тобі треба 👋"

    override val busyReply = "Зачекай, я ще працюю над твоїм попереднім запитом 😊"

    override val fallbackErrorReply = "Щось пішло не так — спробуй ще раз? 🥲"

    override val overloadedReply = "Зараз у мене забагато запитів — дай хвилинку й спробуй ще раз 🙏"
    override val signInRequiredReply = "Моє підключення до AI-сервісу треба поновити — потрібен повторний вхід 🔑"

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

    override val tasksCommandDescription = "Керувати запланованими завданнями"
    override val clearCommandDescription = "Очистити історію переписки"

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "Це голосове триває ${durationSeconds}с — я можу розпізнати щонайбільше ${maxSeconds}с, " +
                "надішли коротше або напиши текстом"

    override fun subscriptionLimitReply(untilReset: Duration?): String =
        untilReset
            ?.let { "Ліміт використання вичерпано — оновиться приблизно за ${waitLabel(it)}, тоді спробуй ще раз ⏳" }
            ?: "Ліміт використання вичерпано — він скоро оновиться, спробуй пізніше 🙏"

    override fun tokenBudgetExhaustedReply(untilReset: Duration): String =
        "Бюджет токенів на сьогодні вичерпано — повертайся приблизно за ${waitLabel(untilReset)} ⏳"

    override fun tokenShareExhaustedReply(untilReset: Duration): String =
        "Бюджет токенів на сьогодні добігає кінця, і твоя частка вже вичерпана — " +
                "решту тримаю для інших. Повертайся приблизно за ${waitLabel(untilReset)} ⏳"

    private fun waitLabel(untilReset: Duration): String =
        untilReset.toComponents { days, hours, minutes, _, _ ->
            when {
                days > 0 -> "$days дн $hours год"
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
