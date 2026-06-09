package com.helltar.vusan.tasks

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.ScheduledAttribution
import com.helltar.vusan.telegram.TelegramDelivery
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration

class TaskScheduler(
    private val repo: TasksRepository,
    private val agentRunner: AgentRunner,
    private val delivery: TelegramDelivery,
    private val history: ChatHistoryRepository,
    private val pollInterval: Duration,
    private val maxLateness: Duration
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            log.info {
                "TaskScheduler started: pollInterval=${pollInterval.inWholeSeconds}s " +
                        "maxLateness=${maxLateness.inWholeMinutes}m"
            }

            while (true) {
                runCatching { tick(Instant.now()) }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.error(it) { "task scheduler tick failed" }
                    }

                delay(pollInterval)
            }
        }

    private suspend fun tick(now: Instant) {
        val due = repo.findDue(now)
        if (due.isEmpty()) return

        for (task in due) {
            runCatching { processOne(task, now) }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.error(it) { "failed to process task id=${task.id}" }
                }
        }
    }

    private suspend fun processOne(task: ScheduledTask, now: Instant) {
        val latenessMillis = now.toEpochMilli() - task.nextFireAt.toEpochMilli()

        if (latenessMillis > maxLateness.inWholeMilliseconds) {
            handleMissed(task, now)
            return
        }

        // reschedule even when the run itself fails: a task left due would be retried on every
        // poll tick, re-running the full agent indefinitely on a persistent error.
        runCatching { fire(task) }
            .onFailure {
                it.rethrowIfCancellation()
                log.error(it) { "task id=${task.id} run failed; rescheduling without retry" }
            }

        rescheduleAfterFire(task, now)
    }

    private suspend fun handleMissed(task: ScheduledTask, now: Instant) {
        val scheduledLabel = formatFire(task.nextFireAt, task.timezone)

        log.warn {
            "task id=${task.id} missed (scheduledFor=$scheduledLabel, " +
                    "late by ${(now.toEpochMilli() - task.nextFireAt.toEpochMilli()) / 1000}s); user offline window"
        }

        delivery
            .sendNotice(
                task.chatId,
                Messages.of(task.language).taskMissedNotice(task.id, task.title, scheduledLabel)
            )

        rescheduleAfterFire(task, now)
    }

    private suspend fun fire(task: ScheduledTask) {
        log.info {
            "firing task id=${task.id} user=${task.userId} chat=${task.chatId} recurrence=[${task.recurrence.display}]"
        }

        val request =
            AgentRequest(
                chatId = task.chatId,
                userId = task.userId,
                messageId = 0L,
                replyToMessageId = null,
                prompt = wrapPrompt(task),
                historyEntry = historyEntry(task),
                messageContext = null,
                chatIsPrivate = task.chatIsPrivate,
                language = task.language
            )

        val result = agentRunner.handleScheduled(request)

        runCatching {
            delivery.sendScheduled(
                result = result,
                chatId = task.chatId,
                userId = task.userId,
                messages = Messages.of(task.language),
                attribution = attributionFor(task)
            )

            if (result.historyTurns.isNotEmpty()) {
                history.appendTurns(task.userId, result.historyTurns)
            }
        }.onFailure {
            it.rethrowIfCancellation()

            log.error(it) {
                "task id=${task.id} fired but delivery/persistence failed; not retrying to avoid duplicates"
            }
        }
    }

    private suspend fun rescheduleAfterFire(task: ScheduledTask, now: Instant) {
        val nextFire = task.recurrence.catchUpAfter(task.nextFireAt, task.timezone, now)

        if (nextFire == null)
            repo.disable(task.id)
        else
            repo.reschedule(task.id, nextFire)
    }

    private fun wrapPrompt(task: ScheduledTask): String =
        buildString {
            append(scheduledTaskOpenTag(task)).append('\n')
            append("This is a scheduled task you set up earlier. Execute it now without asking for confirmation.\n")
            append("Task: ").append(task.prompt).append('\n')
            append("</scheduled_task>")
        }

    private fun historyEntry(task: ScheduledTask): String =
        scheduledTaskOpenTag(task) + task.prompt + "</scheduled_task>"

    private fun scheduledTaskOpenTag(task: ScheduledTask): String =
        buildString {
            append("<scheduled_task")
            task.title?.let { appendXmlAttr("title", it) }
            appendXmlAttr("recurrence", task.recurrence.display)
            append('>')
        }

    private fun attributionFor(task: ScheduledTask): ScheduledAttribution? {
        if (task.chatIsPrivate) return null

        val mention =
            when {
                task.creatorUsername != null -> "@${task.creatorUsername}"
                task.creatorDisplayName != null -> "[${task.creatorDisplayName}](tg://user?id=${task.userId})"
                else -> "user ${task.userId}"
            }

        return ScheduledAttribution(
            creatorMessageId = task.creatorMessageId,
            headerText = "⏰ Scheduled by $mention"
        )
    }
}

private fun StringBuilder.appendXmlAttr(name: String, value: String) {
    append(' ').append(name).append('=').append('"').append(escapeXml(value)).append('"')
}

private fun escapeXml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
