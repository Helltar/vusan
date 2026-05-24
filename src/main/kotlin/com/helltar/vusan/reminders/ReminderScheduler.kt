package com.helltar.vusan.reminders

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.TelegramDelivery
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.toKotlinDuration

class ReminderScheduler(
    private val repo: RemindersRepository,
    private val agentRunner: AgentRunner,
    private val delivery: TelegramDelivery,
    private val history: ChatHistoryRepository,
    private val pollInterval: Duration,
    private val maxLateness: Duration,
) {

    private companion object {
        val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val log = KotlinLogging.logger {}
    }

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            log.info { "ReminderScheduler started: pollInterval=${pollInterval.seconds}s maxLateness=${maxLateness.toMinutes()}m" }

            while (true) {
                runCatching { tick(Instant.now()) }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.error(it) { "reminder scheduler tick failed" }
                    }

                delay(pollInterval.toKotlinDuration())
            }
        }

    private suspend fun tick(now: Instant) {
        val due = repo.findDue(now)
        if (due.isEmpty()) return

        for (reminder in due) {
            runCatching { processOne(reminder, now) }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.error(it) { "failed to process reminder id=${reminder.id}" }
                }
        }
    }

    private suspend fun processOne(reminder: Reminder, now: Instant) {
        val latenessMillis = now.toEpochMilli() - reminder.nextFireAt.toEpochMilli()

        if (latenessMillis > maxLateness.toMillis()) {
            handleMissed(reminder, now)
            return
        }

        fire(reminder)
        rescheduleAfterFire(reminder, now)
    }

    private suspend fun handleMissed(reminder: Reminder, now: Instant) {
        val scheduledLabel = formatFire(reminder.nextFireAt, reminder.timezone)
        log.warn {
            "reminder id=${reminder.id} missed (scheduledFor=$scheduledLabel, late by ${(now.toEpochMilli() - reminder.nextFireAt.toEpochMilli()) / 1000}s); user offline window"
        }

        delivery.sendNotice(reminder.chatId, Messages.reminderMissedNotice(reminder.id, reminder.title, scheduledLabel))

        when (reminder.recurrence) {
            Recurrence.ONCE -> repo.disable(reminder.id)
            else -> {
                val nextFire = reminder.recurrence.catchUpAfter(reminder.nextFireAt, reminder.timezone, now)
                if (nextFire == null) {
                    repo.disable(reminder.id)
                } else {
                    repo.reschedule(reminder.id, nextFire)
                }
            }
        }
    }

    private suspend fun fire(reminder: Reminder) {
        log.info { "firing reminder id=${reminder.id} user=${reminder.userId} chat=${reminder.chatId} recurrence=${reminder.recurrence}" }

        val request =
            AgentRequest(
                chatId = reminder.chatId,
                userId = reminder.userId,
                messageId = 0L,
                replyToMessageId = null,
                prompt = wrapPrompt(reminder),
                historyEntry = historyEntry(reminder),
                messageContext = null,
                repliedPhoto = null,
            )

        val result = agentRunner.handleScheduled(request)

        delivery.sendScheduled(result, chatId = reminder.chatId, userId = reminder.userId)

        if (result.historyTurns.isNotEmpty()) {
            history.appendTurns(reminder.userId, result.historyTurns)
        }
    }

    private suspend fun rescheduleAfterFire(reminder: Reminder, now: Instant) {
        val nextFire = reminder.recurrence.catchUpAfter(reminder.nextFireAt, reminder.timezone, now)
        if (nextFire == null) {
            repo.disable(reminder.id)
        } else {
            repo.reschedule(reminder.id, nextFire)
        }
    }

    private fun wrapPrompt(reminder: Reminder): String =
        buildString {
            append("<scheduled_task")
            reminder.title?.let { append(" title=\"").append(escapeXml(it)).append('"') }
            append(" recurrence=\"").append(reminder.recurrence.name).append('"')
            append(">\n")
            append("This is a scheduled task you set up earlier. Execute it now without asking for confirmation.\n")
            append("Task: ").append(reminder.prompt).append('\n')
            append("</scheduled_task>")
        }

    private fun historyEntry(reminder: Reminder): String =
        buildString {
            append("<scheduled_task")
            reminder.title?.let { append(" title=\"").append(escapeXml(it)).append('"') }
            append(" recurrence=\"").append(reminder.recurrence.name).append('"')
            append(">").append(reminder.prompt).append("</scheduled_task>")
        }

    private fun formatFire(instant: Instant, tz: ZoneId): String {
        val zoned = ZonedDateTime.ofInstant(instant, tz)
        return "${DISPLAY.format(zoned)} ${tz.id}"
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
