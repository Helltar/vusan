package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.common.escapeHtml
import kotlin.time.Duration

internal object RussianMessages : Messages {

    override val startReply = "Привет. Просто скажи, что тебе нужно"

    override val busyReply = "Подожди, я ещё с твоим предыдущим"

    override val fallbackErrorReply = "Что-то сломалось, попробуй ещё раз"

    override val overloadedReply = "Сейчас слишком много запросов, дай минуту и попробуй снова"
    override val signInRequiredReply = "Мой вход в AI-сервис слетел, нужен повторный вход"

    override val contentPolicyReply = "Это не пропустило, сформулируй иначе"

    override val formattingAsFileNotice = "Телеграм не показал форматирование, поэтому полный ответ файлом"

    override val privateBlockedNotice =
        "Хочу написать тебе в личные, а не получается. Открой мой чат, нажми /start и спроси снова"

    override val conversationClearedReply =
        "История нашей переписки в этом чате очищена. Другие чаты, память и запланированные задачи на месте"

    override val turnStoppedNotice = "Останавливаю. Что уже отправлено, остаётся, история и файлы не тронуты"

    override val nothingToStopReply = "Сейчас ничего не делаю, нечего останавливать"

    override val turnStopButton = "⏹ Остановить"

    override val turnStopNotOwnerAlert = "Это запрос другого пользователя."

    override val voiceEmptyReply = "В этом голосовом ничего не слышно, попробуй ещё раз или напиши текстом"

    override val voiceTranscriptionFailedReply = "Не разобрать это голосовое, напиши текстом"

    override val inlineChoiceNotOwnerAlert = "Этот выбор был предназначен другому пользователю."

    override val inlineChoiceUnavailableAlert = "Этот выбор уже недоступен."

    override val inlineChoiceErrorAlert = "Не удалось применить выбор — попробуй ещё раз."

    override val taskMenuNotOwnerAlert = "Это меню задач принадлежит другому пользователю."

    override val taskMenuUnavailableAlert = "Эта задача уже недоступна."

    override val taskMenuPastOnceAlert = "Время этой одноразовой задачи уже прошло, поэтому её нельзя возобновить."

    override val taskMenuErrorAlert = "Не удалось обновить задачу — попробуй ещё раз."

    override val taskMenuRefreshButton = "🔄 Обновить"

    override val taskMenuBackButton = "↩️ Назад"

    override val taskMenuDeleteButton = "🗑 Удалить"

    override val tasksCommandDescription = "Управлять запланированными задачами"
    override val clearCommandDescription = "Очистить историю переписки"
    override val stopCommandDescription = "Остановить то, что я сейчас делаю"

    override val ephemeralChatReply = "Это видим только мы с тобой, а отвечаю я всем. Напиши мне обычным сообщением"

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "Это голосовое на ${durationSeconds}с, а я слушаю до ${maxSeconds}с. Пришли короче или напиши текстом"

    override fun subscriptionLimitReply(untilReset: Duration?): String =
        untilReset
            ?.let { "Лимит исчерпан, обновится примерно через ${waitLabel(it)}, тогда попробуй снова" }
            ?: "Лимит исчерпан, скоро обновится, попробуй позже"

    private fun waitLabel(untilReset: Duration): String =
        untilReset.toComponents { days, hours, minutes, _, _ ->
            when {
                days > 0 -> "$days дн $hours ч"
                hours > 0 -> "$hours ч $minutes мин"
                minutes > 0 -> "$minutes мин"
                else -> "минутку"
            }
        }

    override fun inlineChoiceSelected(option: String) = "✅ Выбрано: $option"

    override fun taskMenuTitle(currentChatOnly: Boolean): String =
        if (currentChatOnly)
            "<b>🗓 Твои запланированные задачи в этом чате</b>"
        else
            "<b>🗓 Твои запланированные задачи</b>"

    override fun taskMenuCapacity(currentChatOnly: Boolean, listed: Int, total: Int, limit: Int): String =
        if (currentChatOnly)
            "<i>В этом чате: $listed\nВо всех чатах: $total · лимит: $limit</i>"
        else
            "<i>Задач: $total · лимит: $limit</i>"

    override fun taskMenuEmpty(currentChatOnly: Boolean): String =
        if (currentChatOnly)
            "У тебя нет запланированных задач в этом чате."
        else
            "У тебя нет запланированных задач."

    override fun taskMenuHiddenNotice(hidden: Int) =
        "<i>Ещё $hidden сюда не поместилось — просто спроси о них своими словами.</i>"

    override fun taskMenuItem(
        id: Long,
        label: String,
        nextFire: String,
        recurrence: String,
        paused: Boolean,
    ): String =
        buildString {
            append("<b>#$id · $label</b>\n")
            append("🕒 $nextFire\n")
            append("🔁 $recurrence\n")
            append(if (paused) "⏸ На паузе" else "🟢 Активна")
        }

    override fun taskMenuPauseButton(id: Long) = "⏸ Пауза #$id"

    override fun taskMenuResumeButton(id: Long) = "▶️ Возобновить #$id"

    override fun taskMenuCancelButton(id: Long) = "🗑 Отменить #$id"

    override fun taskMenuDeleteConfirmation(id: Long, label: String): String =
        "<b>Удалить задачу #$id · $label?</b>\n\nЭто действие нельзя отменить."

    override fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { " «$it»" } ?: ""

        return "⏰ Задача #$id$label, запланированная на $scheduledFor, пропущена: меня не было онлайн."
    }

    override fun taskFailedNotice(id: Long, title: String?): String {
        val label = title?.let { " «$it»" } ?: ""

        return "⚠️ Задача #$id$label не выполнена: даже за несколько попыток ничего не вышло."
    }

    override fun taskScheduledByNotice(mention: String) = "⏰ Запланировано: $mention"

    override fun taskFollowUpNotice(mention: String) = "💬 Возвращаюсь к разговору с $mention"

    override fun fallbackModelNote(model: String, primaryBackIn: Duration?): String =
        "↳ на резервной модели: <code>${model.escapeHtml()}</code>" +
                primaryBackIn?.let { "\n↳ основная будет примерно через <b>${waitLabel(it)}</b>" }.orEmpty()

    override fun progressLabel(activity: ToolActivity): String =

        when (activity) {
            ToolActivity.WRITING -> "Пишу ответ"
            ToolActivity.SEARCHING_WEB -> "Ищу в интернете"
            ToolActivity.READING_PAGE -> "Читаю страницу"
            ToolActivity.READING_CHANNEL -> "Читаю канал"
            ToolActivity.READING_TRANSCRIPT -> "Читаю субтитры к видео"
            ToolActivity.READING_CHAT_LOG -> "Читаю историю чата"
            ToolActivity.SEARCHING_IMAGES -> "Ищу картинки"
            ToolActivity.SEARCHING_GIF -> "Ищу GIF"
            ToolActivity.DRAWING -> "Рисую"
            ToolActivity.RUNNING_CODE -> "Выполняю код"
            ToolActivity.LOOKING_AT_IMAGE -> "Смотрю на изображение"
            ToolActivity.WATCHING_VIDEO -> "Смотрю видео"
            ToolActivity.DOWNLOADING_VIDEO -> "Скачиваю видео"
            ToolActivity.DOWNLOADING_AUDIO -> "Достаю аудио"
            ToolActivity.SENDING_FILE -> "Готовлю файл"
            ToolActivity.SPEAKING -> "Записываю голосовое"
            ToolActivity.REMEMBERING -> "Обновляю память"
            ToolActivity.MANAGING_TASKS -> "Обновляю запланированные задачи"
        }
}
