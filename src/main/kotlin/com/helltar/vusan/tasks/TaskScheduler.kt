package com.helltar.vusan.tasks

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.delivery.ScheduledAttribution
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TaskScheduler(
    private val repo: TasksRepository,
    private val agentRunner: AgentRunner,
    private val delivery: TelegramDelivery,
    private val maxLateness: Duration
) {

    private companion object {
        val log = KotlinLogging.logger {}

        // implementation detail, not policy: a 30s tick is cheap (one SQLite query per tick)
        // and fine-grained enough for the 5-minute minimum task interval
        val POLL_INTERVAL = 30.seconds

        // a failed run delivers nothing, so running it again cannot duplicate output. attempts stay few
        // and the backoff short (30s, then 60s): a tick processes its due tasks one after another, so a
        // task that keeps failing holds up everything due behind it.
        const val MAX_ATTEMPTS = 3
        val RETRY_BACKOFF = 30.seconds
    }

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            log.info {
                "TaskScheduler started: pollInterval=${POLL_INTERVAL.inWholeSeconds}s " +
                        "maxLateness=${maxLateness.inWholeMinutes}m"
            }

            while (true) {
                runCatching { tick(Instant.now()) }
                    .onFailure {
                        it.rethrowIfCancellation()
                        log.error(it) { "task scheduler tick failed" }
                    }

                delay(POLL_INTERVAL)
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

        // reschedule even when every attempt fails: a task left due would be picked up again on every
        // poll tick, re-running the full agent indefinitely on a persistent error.
        runCatching { fire(task) }
            .onFailure {
                it.rethrowIfCancellation()
                log.error(it) { "task id=${task.id} run failed; rescheduling" }
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
        for (attempt in 1..MAX_ATTEMPTS) {
            if (runAttempt(task, attempt)) return

            if (attempt < MAX_ATTEMPTS) {
                val backoff = RETRY_BACKOFF * attempt

                log.warn {
                    "task id=${task.id} attempt=$attempt/$MAX_ATTEMPTS failed; " +
                            "retrying in ${backoff.inWholeSeconds}s"
                }

                delay(backoff)
            }
        }

        log.error { "task id=${task.id} failed on all $MAX_ATTEMPTS attempts; nothing was delivered" }

        delivery.sendNotice(task.chatId, Messages.of(task.language).taskFailedNotice(task.id, task.title))
    }

    // one full run of the task. `false` means the run itself failed with nothing delivered, so it is safe
    // to repeat; a failed delivery is not retried, since part of the answer may already be in the chat.
    private suspend fun runAttempt(task: ScheduledTask, attempt: Int): Boolean {
        log.info {
            "firing task id=${task.id} user=${task.userId} chat=${task.chatId} " +
                    "recurrence=[${task.recurrence.display}] attempt=$attempt/$MAX_ATTEMPTS"
        }

        val request =
            AgentRequest(
                chatId = task.chatId,
                userId = task.userId,
                messageId = 0L,
                replyToMessageId = null,
                prompt = scheduledTaskPrompt(task, attempt),
                conversationEntry = conversationEntry(task),
                messageContext = null,
                chatIsPrivate = task.chatIsPrivate,
                language = task.language
            )

        // the agent answers its own failures with a canned reply instead of throwing, so the flag is the
        // only thing separating "the task did not run" from a real answer.
        val result =
            runCatching { agentRunner.handleScheduled(request) }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.error(it) { "task id=${task.id} run failed" }
                }
                .getOrNull()
                ?.takeUnless { it.failed }
                ?: return false

        runCatching {
            delivery.sendScheduled(
                result = result,
                chatId = task.chatId,
                userId = task.userId,
                messages = Messages.of(task.language),
                attribution = attributionFor(task)
            )
        }.onFailure {
            it.rethrowIfCancellation()

            log.error(it) { "task id=${task.id} fired but delivery failed; not retrying to avoid duplicates" }
        }

        return true
    }

    private suspend fun rescheduleAfterFire(task: ScheduledTask, now: Instant) {
        val nextFire = task.recurrence.catchUpAfter(task.nextFireAt, task.timezone, now)

        if (nextFire == null)
            repo.disable(task.id)
        else
            repo.reschedule(task.id, nextFire)
    }

    // the retry is not stored: history keeps the task as the user wrote it, without the retry hint.
    private fun conversationEntry(task: ScheduledTask): String =
        scheduledTaskOpenTag(task) + task.prompt + "</scheduled_task>"

    private fun attributionFor(task: ScheduledTask): ScheduledAttribution? {
        if (task.chatIsPrivate) return null

        val mention =
            when {
                task.creatorUsername != null -> "@${task.creatorUsername}"
                task.creatorDisplayName != null -> "[${task.creatorDisplayName}](tg://user?id=${task.userId})"
                else -> "user ${task.userId}"
            }

        val messages = Messages.of(task.language)

        return ScheduledAttribution(
            creatorMessageId = task.creatorMessageId,
            headerText =
                if (task.selfInitiated)
                    messages.taskFollowUpNotice(mention)
                else
                    messages.taskScheduledByNotice(mention)
        )
    }
}

// a failed run left no history behind, so the retry has to say in the prompt what went wrong. the usual
// failure is a run that spends its whole step budget researching and never delivers.
private const val RETRY_HINT =
    "An earlier attempt at this task ended without delivering anything. " +
            "Get to the result faster this time: gather only what the task needs, then send it."

internal fun scheduledTaskPrompt(task: ScheduledTask, attempt: Int): String =
    buildString {
        append(scheduledTaskOpenTag(task)).append('\n')
        append("This is a scheduled task you set up earlier. Execute it now without asking for confirmation.\n")
        append("Task: ").append(task.prompt).append('\n')
        if (attempt > 1) append(RETRY_HINT).append('\n')
        append("</scheduled_task>")
    }

private fun scheduledTaskOpenTag(task: ScheduledTask): String =
    buildString {
        append("<scheduled_task")
        task.title?.let { appendXmlAttr("title", it) }
        appendXmlAttr("recurrence", task.recurrence.display)
        append('>')
    }

private fun StringBuilder.appendXmlAttr(name: String, value: String) {
    append(' ').append(name).append('=').append('"').append(escapeXml(value)).append('"')
}

private fun escapeXml(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
