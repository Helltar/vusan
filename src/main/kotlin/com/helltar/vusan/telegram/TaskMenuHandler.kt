package com.helltar.vusan.telegram

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tasks.ScheduledTask
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.tasks.formatFire
import com.helltar.vusan.tasks.nextFireAfterResume
import java.time.Instant
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient

internal class TaskMenuHandler(
    private val client: TelegramClient,
    private val tasks: TasksRepository,
    private val maxTasksPerUser: Int,
    private val now: () -> Instant = Instant::now
) {

    private companion object {
        const val CALLBACK_PREFIX = "tasks:"
        const val MAX_TASK_LABEL_CHARS = 120

        // MAX_TASKS_PER_USER is configurable, so the rendered list has to stay under Telegram's
        // 4096-character message limit however high it is set. the slack covers the header and notice.
        const val MAX_MENU_ITEMS_CHARS = 3600

        const val ITEM_SEPARATOR = "\n\n"
    }

    fun handles(callbackData: String?): Boolean =
        callbackData?.startsWith(CALLBACK_PREFIX) == true

    suspend fun sendMenu(
        chatId: Long,
        userId: Long,
        replyToMessageId: Long,
        chatIsPrivate: Boolean,
        messages: Messages
    ) {
        val menu = buildMenu(userId, chatId, chatIsPrivate, messages)

        sendTextMessage(
            client = client,
            chatId = chatId,
            text = menu.text,
            parseMode = ParseMode.HTML,
            replyParameters = replyParameters(replyToMessageId),
            replyMarkup = menu.keyboard
        )
    }

    suspend fun handleCallback(
        callbackQueryId: String,
        callbackData: String,
        userId: Long,
        chatId: Long,
        messageId: Int,
        chatIsPrivate: Boolean,
        messages: Messages
    ) {
        try {
            val action =
                TaskMenuAction.parse(callbackData)
                    ?: return answerCallbackQuery(
                        client,
                        callbackQueryId,
                        messages.taskMenuUnavailableAlert,
                        showAlert = true
                    )

            if (action.ownerId != userId) {
                answerCallbackQuery(
                    client,
                    callbackQueryId,
                    messages.taskMenuNotOwnerAlert,
                    showAlert = true
                )
                return
            }

            val scopedChatId = chatId.takeUnless { chatIsPrivate }

            when (action) {
                is TaskMenuAction.Refresh,
                is TaskMenuAction.Back -> editMenu(chatId, messageId, userId, chatIsPrivate, messages)

                is TaskMenuAction.Pause -> {
                    if (!tasks.pauseForUser(userId, action.taskId, scopedChatId)) {
                        editMenu(chatId, messageId, userId, chatIsPrivate, messages)
                        return answerUnavailable(callbackQueryId, messages)
                    }

                    editMenu(chatId, messageId, userId, chatIsPrivate, messages)
                }

                is TaskMenuAction.Resume -> {
                    val task = tasks.findEnabledForUser(userId, action.taskId, scopedChatId)

                    if (task == null) {
                        editMenu(chatId, messageId, userId, chatIsPrivate, messages)
                        return answerUnavailable(callbackQueryId, messages)
                    }

                    val nextFireAt = task.nextFireAfterResume(now())

                    if (nextFireAt == null) {
                        answerCallbackQuery(
                            client,
                            callbackQueryId,
                            messages.taskMenuPastOnceAlert,
                            showAlert = true
                        )
                        return
                    }

                    if (!tasks.resumeForUser(userId, action.taskId, nextFireAt, scopedChatId)) {
                        editMenu(chatId, messageId, userId, chatIsPrivate, messages)
                        return answerUnavailable(callbackQueryId, messages)
                    }

                    editMenu(chatId, messageId, userId, chatIsPrivate, messages)
                }

                is TaskMenuAction.ConfirmDelete -> {
                    val task = tasks.findEnabledForUser(userId, action.taskId, scopedChatId)

                    if (task == null) {
                        editMenu(chatId, messageId, userId, chatIsPrivate, messages)
                        return answerUnavailable(callbackQueryId, messages)
                    }

                    editDeleteConfirmation(chatId, messageId, task, messages)
                }

                is TaskMenuAction.Delete -> {
                    if (!tasks.deleteEnabledForUser(userId, action.taskId, scopedChatId)) {
                        editMenu(chatId, messageId, userId, chatIsPrivate, messages)
                        return answerUnavailable(callbackQueryId, messages)
                    }

                    editMenu(chatId, messageId, userId, chatIsPrivate, messages)
                }
            }

            answerCallbackQuery(client, callbackQueryId)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()

            runCatching {
                answerCallbackQuery(
                    client,
                    callbackQueryId,
                    messages.taskMenuErrorAlert,
                    showAlert = true
                )
            }.onFailure { it.rethrowIfCancellation() }

            throw error
        }
    }

    suspend fun answerUnavailable(callbackQueryId: String, messages: Messages) {
        answerCallbackQuery(
            client,
            callbackQueryId,
            messages.taskMenuUnavailableAlert,
            showAlert = true
        )
    }

    private suspend fun editMenu(
        chatId: Long,
        messageId: Int,
        userId: Long,
        chatIsPrivate: Boolean,
        messages: Messages
    ) {
        val menu = buildMenu(userId, chatId, chatIsPrivate, messages)
        editIgnoringUnchanged(chatId, messageId, menu.text, menu.keyboard)
    }

    private suspend fun editDeleteConfirmation(
        chatId: Long,
        messageId: Int,
        task: ScheduledTask,
        messages: Messages
    ) {
        val keyboard =
            InlineKeyboardMarkup.builder()
                .keyboard(
                    listOf(
                        InlineKeyboardRow(
                            callbackButton(
                                messages.taskMenuDeleteButton,
                                TaskMenuAction.Delete(task.userId, task.id).serialize()
                            ),
                            callbackButton(
                                messages.taskMenuBackButton,
                                TaskMenuAction.Back(task.userId).serialize()
                            )
                        )
                    )
                )
                .build()

        editIgnoringUnchanged(
            chatId,
            messageId,
            messages.taskMenuDeleteConfirmation(task.id, task.menuLabel().escapeHtml()),
            keyboard
        )
    }

    private suspend fun buildMenu(
        userId: Long,
        chatId: Long,
        chatIsPrivate: Boolean,
        messages: Messages
    ): TaskMenu {
        val currentChatOnly = !chatIsPrivate
        val listedTasks = tasks.listEnabledByUser(userId, chatId.takeIf { currentChatOnly })
        val totalTasks = if (currentChatOnly) tasks.countEnabledByUser(userId) else listedTasks.size

        val shownTasks = listedTasks.fittingInMenu(messages)
        val hiddenTasks = listedTasks.size - shownTasks.size

        val text =
            buildString {
                append(messages.taskMenuTitle(currentChatOnly)).append('\n')
                append(
                    messages.taskMenuCapacity(
                        currentChatOnly = currentChatOnly,
                        listed = listedTasks.size,
                        total = totalTasks,
                        limit = maxTasksPerUser
                    )
                ).append(ITEM_SEPARATOR)

                if (shownTasks.isEmpty())
                    append(messages.taskMenuEmpty(currentChatOnly))
                else
                    append(shownTasks.joinToString(ITEM_SEPARATOR) { it.text })

                if (hiddenTasks > 0)
                    append(ITEM_SEPARATOR).append(messages.taskMenuHiddenNotice(hiddenTasks))
            }

        val rows: List<InlineKeyboardRow> =
            shownTasks.map { (task, _) ->
                InlineKeyboardRow(
                    callbackButton(
                        if (task.paused)
                            messages.taskMenuResumeButton(task.id)
                        else
                            messages.taskMenuPauseButton(task.id),
                        if (task.paused)
                            TaskMenuAction.Resume(userId, task.id).serialize()
                        else
                            TaskMenuAction.Pause(userId, task.id).serialize()
                    ),
                    callbackButton(
                        messages.taskMenuCancelButton(task.id),
                        TaskMenuAction.ConfirmDelete(userId, task.id).serialize()
                    )
                )
            } + listOf(
                InlineKeyboardRow(
                    callbackButton(
                        messages.taskMenuRefreshButton,
                        TaskMenuAction.Refresh(userId).serialize()
                    )
                )
            )

        return TaskMenu(
            text = text,
            keyboard = InlineKeyboardMarkup.builder().keyboard(rows).build()
        )
    }

    private suspend fun editIgnoringUnchanged(
        chatId: Long,
        messageId: Int,
        text: String,
        keyboard: InlineKeyboardMarkup
    ) {
        runCatching {
            editTextMessage(client, chatId, messageId, text, keyboard, ParseMode.HTML)
        }.recoverCatching { error ->
            error.rethrowIfCancellation()
            if (!error.isMessageNotModified()) throw error
        }.getOrThrow()
    }

    // renders items while they fit the text budget, always keeping at least one so a single oversized
    // task cannot make the menu look empty. what does not fit stays reachable through the task tools.
    private fun List<ScheduledTask>.fittingInMenu(messages: Messages): List<MenuItem> {
        val fitting = mutableListOf<MenuItem>()
        var used = 0

        for (task in this) {
            val item =
                MenuItem(
                    task = task,
                    text =
                        messages.taskMenuItem(
                            id = task.id,
                            label = task.menuLabel().escapeHtml(),
                            nextFire = task.menuFireHtml(),
                            recurrence = task.recurrence.menuHtml(),
                            paused = task.paused
                        )
                )

            val cost = item.text.length + ITEM_SEPARATOR.length

            if (fitting.isNotEmpty() && used + cost > MAX_MENU_ITEMS_CHARS) break

            fitting += item
            used += cost
        }

        return fitting
    }

    private fun ScheduledTask.menuLabel(): String =
        title
            ?.collapseWhitespaceAndCap(MAX_TASK_LABEL_CHARS)
            ?: prompt.collapseWhitespaceAndCap(MAX_TASK_LABEL_CHARS)
            ?: "#$id"

    private fun ScheduledTask.menuFireHtml(): String {
        val fire = formatFire(nextFireAt, timezone)
        val localTime = fire.substringBeforeLast(' ').replace('T', ' ')
        return "$localTime · ${timezone.id.escapeHtml()}"
    }

    private fun Recurrence.menuHtml(): String =
        when (this) {
            Recurrence.Once -> "once"
            is Recurrence.Every -> "every ${interval.toString().escapeHtml()}"
            is Recurrence.Cron -> "cron · <code>${expression.escapeHtml()}</code>"
        }

    private fun callbackButton(text: String, data: String): InlineKeyboardButton =
        InlineKeyboardButton.builder()
            .text(text)
            .callbackData(data)
            .build()

    private data class MenuItem(
        val task: ScheduledTask,
        val text: String
    )

    private data class TaskMenu(
        val text: String,
        val keyboard: InlineKeyboardMarkup
    )
}

private sealed interface TaskMenuAction {
    val ownerId: Long

    fun serialize(): String

    data class Refresh(override val ownerId: Long) : TaskMenuAction {
        override fun serialize() = "$PREFIX$ownerId:refresh"
    }

    data class Back(override val ownerId: Long) : TaskMenuAction {
        override fun serialize() = "$PREFIX$ownerId:back"
    }

    data class Pause(override val ownerId: Long, val taskId: Long) : TaskMenuAction {
        override fun serialize() = "$PREFIX$ownerId:pause:$taskId"
    }

    data class Resume(override val ownerId: Long, val taskId: Long) : TaskMenuAction {
        override fun serialize() = "$PREFIX$ownerId:resume:$taskId"
    }

    data class ConfirmDelete(override val ownerId: Long, val taskId: Long) : TaskMenuAction {
        override fun serialize() = "$PREFIX$ownerId:confirm:$taskId"
    }

    data class Delete(override val ownerId: Long, val taskId: Long) : TaskMenuAction {
        override fun serialize() = "$PREFIX$ownerId:delete:$taskId"
    }

    companion object {
        private const val PREFIX = "tasks:"

        fun parse(raw: String): TaskMenuAction? {
            if (!raw.startsWith(PREFIX)) return null

            val parts = raw.removePrefix(PREFIX).split(':')
            val ownerId = parts.firstOrNull()?.toLongOrNull()?.takeIf { it > 0L } ?: return null

            return when {
                parts.size == 2 && parts[1] == "refresh" -> Refresh(ownerId)
                parts.size == 2 && parts[1] == "back" -> Back(ownerId)
                parts.size == 3 -> {
                    val taskId = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null

                    when (parts[1]) {
                        "pause" -> Pause(ownerId, taskId)
                        "resume" -> Resume(ownerId, taskId)
                        "confirm" -> ConfirmDelete(ownerId, taskId)
                        "delete" -> Delete(ownerId, taskId)
                        else -> null
                    }
                }

                else -> null
            }
        }
    }
}
