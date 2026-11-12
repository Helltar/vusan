package com.helltar.vusan.tasks

import com.helltar.vusan.agent.AgentRequest
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.presence.LeaveAloneRepository
import com.helltar.vusan.agent.scheduledTaskBlock
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.runInOwnJob
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.delivery.DeliveryOutcome
import com.helltar.vusan.delivery.OutputDelivery
import com.helltar.vusan.delivery.TurnDelivery
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.ChatProfile
import com.helltar.vusan.request.ChatProfileLookup
import com.helltar.vusan.request.ChatRef
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TaskScheduler(
    private val repo: TasksRepository,
    private val agentRunner: AgentRunner,
    private val delivery: OutputDelivery,
    private val chatProfiles: ChatProfileLookup,
    // no default: an empty policy allows nobody, and a scheduler silently firing nothing is worse than one
    // that refuses to build.
    private val accessPolicy: AccessPolicy,
    // who asked not to be written to: a follow-up of the bot's own would break that
    private val leaveAlone: LeaveAloneRepository? = null,
    private val clock: () -> Instant = Instant::now,
) {

    private sealed interface FireOutcome {

        data class Delivered(val outcome: DeliveryOutcome) : FireOutcome

        data object RunFailed : FireOutcome

        data object Stopped : FireOutcome
    }

    fun launchIn(scope: CoroutineScope): Job =
        scope.launch {
            log.info {
                "TaskScheduler started: pollInterval=${POLL_INTERVAL.inWholeSeconds}s " +
                        "maxLateness=${MAX_LATENESS.inWholeMinutes}m"
            }

            while (true) {
                runCatching { tick(clock()) }
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
            runCatching { processOne(task.id) }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.error(it) { "failed to process task id=${task.id}" }
                }
        }
    }

    // a fire is a whole agent turn, so each task is judged by its row and the clock as they stand now;
    // otherwise a task behind a long fire would be judged late by the tick's time and moved to a past slot.
    private suspend fun processOne(id: Long) {
        val now = clock()

        val task =
            repo.findDue(id, now) ?: run {
                log.info { "task id=$id skipped: it is no longer due" }
                return
            }

        // a task fires on its owner's behalf, so losing access must stop the work that runs without them too.
        if (!accessPolicy.allows(task.scope.chat, task.scope.user)) {
            skipDenied(task, now)
            return
        }

        // a follow-up is the bot writing unasked, which a left-alone person was promised not to get. dropped
        // rather than held: asking how the exam went a week later is not checking back
        if (task.isHeldBy(leaveAlone, now)) {
            log.info { "task id=${task.id} skipped: a follow-up for ${task.scope}, who asked to be left alone" }
            rescheduleAfterFire(task, now)
            return
        }

        val latenessMillis = now.toEpochMilli() - task.nextFireAt.toEpochMilli()

        if (latenessMillis > MAX_LATENESS.inWholeMilliseconds) {
            handleMissed(task, now)
            return
        }

        // reschedule even when every attempt fails, or a persistent error would re-run the agent on every
        // tick.
        val outcome =
            runCatching { fire(task) }
                .getOrElse {
                    it.rethrowIfCancellation()
                    log.error(it) { "task id=${task.id} run failed; rescheduling" }
                    DeliveryOutcome.Handled
                }

        if (!parkedBy(task, outcome)) rescheduleAfterFire(task, now)
    }

    // whether [outcome] stopped the task where it stands: paused tasks keep their slot, and resuming moves
    // a recurring one on.
    private suspend fun parkedBy(task: ScheduledTask, outcome: DeliveryOutcome): Boolean =
        when (outcome) {
            DeliveryOutcome.Handled -> false

            DeliveryOutcome.Unreachable -> {
                parkTasksOfUnreachableChat(task.scope.chat)
                true
            }

            DeliveryOutcome.PrivateChatClosed -> parkedByClosedPrivateChat(task)
        }

    // a task that only writes privately has nowhere left to write, so it stops until its owner opens the
    // chat and resumes it; firing on would repeat this notice at every slot. one that was sent there by a
    // single turn's choice keeps its schedule, and the chat hears what an asking turn would have been told.
    private suspend fun parkedByClosedPrivateChat(task: ScheduledTask): Boolean {
        val messages = Messages.of(task.language)

        if (!task.deliverPrivately) {
            delivery.notify(task.destination, messages.privateBlockedNotice)
            return false
        }

        repo.pauseForUser(task.scope.user, task.id)
        log.warn { "task id=${task.id} paused: the private chat of ${task.scope.user} is closed to the bot" }
        delivery.notify(task.destination, messages.taskPrivateChatClosedNotice(task.id, task.title))

        return true
    }

    private suspend fun notifyAbout(task: ScheduledTask, text: String): DeliveryOutcome =
        if (task.deliverPrivately)
            delivery.notifyPrivately(task.scope.user, text)
        else
            delivery.notify(task.destination, text)

    // the schedule is kept and never reports a miss, so regaining access resumes the task without a backlog:
    // a recurring one moves past the denied fire, a one-time one waits in place.
    private suspend fun skipDenied(task: ScheduledTask, now: Instant) {
        val reason = accessPolicy.denialReason(task.scope.chat, task.scope.user)

        if (task.recurrence.nextAfter(task.nextFireAt, task.timezone) == null) {
            log.debug { "task id=${task.id} waits: ${task.scope} is $reason" }
            return
        }

        log.info { "task id=${task.id} skipped: ${task.scope} is $reason" }
        rescheduleAfterFire(task, now)
    }

    private suspend fun handleMissed(task: ScheduledTask, now: Instant) {
        val scheduledLabel = formatFire(task.nextFireAt, task.timezone)

        log.warn {
            "task id=${task.id} missed (scheduledFor=$scheduledLabel, " +
                    "late by ${(now.toEpochMilli() - task.nextFireAt.toEpochMilli()) / 1000}s); user offline window"
        }

        val outcome =
            notifyAbout(task, Messages.of(task.language).taskMissedNotice(task.id, task.title, scheduledLabel))

        // the notice costs one API call and the run a whole agent turn, so a chat refusing even the notice is
        // caught here.
        if (!parkedBy(task, outcome)) rescheduleAfterFire(task, now)
    }

    // what became of the answer, or of the notice standing in for it: a task that failed is still `Handled`.
    private suspend fun fire(task: ScheduledTask): DeliveryOutcome {
        for (attempt in 1..MAX_ATTEMPTS) {
            when (val fired = runAttempt(task, attempt)) {
                is FireOutcome.Delivered -> return fired.outcome
                FireOutcome.Stopped -> return reportStopped(task)
                FireOutcome.RunFailed -> Unit
            }

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

        return notifyAbout(task, Messages.of(task.language).taskFailedNotice(task.id, task.title))
    }

    // the bot can no longer post here (removed, blocked or muted). each task would run the whole agent only
    // to fail at the send, so the whole chat is parked at once.
    private suspend fun parkTasksOfUnreachableChat(chat: ChatRef) {
        val paused = repo.pauseAllInChat(chat)

        log.warn { "chat=[$chat] is unreachable; paused $paused scheduled task(s) there" }
    }

    // one full run. `RunFailed` means nothing was delivered, so it is safe to repeat; a failed delivery is
    // not retried, since part of the answer may be in the chat.
    private suspend fun runAttempt(task: ScheduledTask, attempt: Int): FireOutcome {
        log.info {
            "firing task id=${task.id} ${task.scope} " +
                    "recurrence=[${task.recurrence.display}] attempt=$attempt/$MAX_ATTEMPTS"
        }

        val request = scheduledAgentRequest(task, attempt, chatProfileFor(task))

        // `/stop` cancels by conversation, and a fire shares the name with the user's own turns there;
        // without its own job the stop would reach the scheduler's loop and every later task.
        return runInOwnJob { deliverTurn(task, request) } ?: FireOutcome.Stopped
    }

    private suspend fun deliverTurn(task: ScheduledTask, request: AgentRequest): FireOutcome {
        // the agent answers its own failures with a canned reply, so the flag alone tells "did not run" from
        // a real answer.
        val result =
            runCatching { agentRunner.handleQueued(request) }
                .onFailure {
                    it.rethrowIfCancellation()
                    log.error(it) { "task id=${task.id} run failed" }
                }
                .getOrNull()
                ?.takeUnless { it.failed }
                ?: return FireOutcome.RunFailed

        val outcome =
            runCatching {
                delivery.deliver(
                    TurnDelivery(
                        result = result,
                        destination = task.destination,
                        recipient = task.scope.user,
                        language = task.language,
                        attribution = task.attribution,
                    ),
                )
            }.getOrElse {
                it.rethrowIfCancellation()

                log.error(it) { "task id=${task.id} fired but delivery failed; not retrying to avoid duplicates" }

                DeliveryOutcome.Handled
            }

        return FireOutcome.Delivered(outcome)
    }

    // matters most for a task: nobody waits to hear the chat refused, and the whole run is spent before the
    // first send would say so. what goes to the owner's private chat is not held to the group's limits.
    private suspend fun chatProfileFor(task: ScheduledTask): ChatProfile =
        when {
            task.chatIsPrivate -> ChatProfile.NONE
            task.deliverPrivately -> chatProfiles.of(task.scope.chat).copy(capabilities = ChatCapabilities.UNRESTRICTED)
            else -> chatProfiles.of(task.scope.chat)
        }

    // a fire the user stopped is not a failure and is not retried: the recurrence moves on as after a
    // delivery. `/stop` itself says nothing, so the notice is left to the stopped fire.
    private suspend fun reportStopped(task: ScheduledTask): DeliveryOutcome {
        log.info { "task id=${task.id} was stopped by its owner" }

        return notifyAbout(task, Messages.of(task.language).turnStoppedNotice)
    }

    private suspend fun rescheduleAfterFire(task: ScheduledTask, now: Instant) {
        val nextFire = task.recurrence.catchUpAfter(task.nextFireAt, task.timezone, now)

        val moved =
            if (nextFire == null)
                repo.deleteFinished(task.id, task.nextFireAt)
            else
                repo.reschedule(task.id, task.nextFireAt, nextFire)

        if (!moved) {
            log.info { "task id=${task.id} was retimed or removed while it fired; its own schedule stands" }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}

        // a 30s tick is one cheap query and fine-grained enough for the 5-minute minimum interval
        val POLL_INTERVAL = 30.seconds

        // how late a due task may still run. an hour covers a restart or short outage; later ones are
        // announced as missed rather than run out of place.
        val MAX_LATENESS = 60.minutes

        // a failed run delivered nothing, so a retry cannot duplicate output. few attempts and a short
        // backoff (30s, 60s), since a tick runs its due tasks in sequence and a failing one holds up the
        // rest.
        const val MAX_ATTEMPTS = 3
        val RETRY_BACKOFF = 30.seconds
    }
}

/**
 * Whether [requests] hold this task back: a follow-up the bot set itself for a person whose leave-alone
 * request in its chat holds at [now], private or group. A task the person asked for fires.
 */
internal suspend fun ScheduledTask.isHeldBy(requests: LeaveAloneRepository?, now: Instant): Boolean {
    if (!selfInitiated || requests == null) return false

    return requests.activeUntil(scope.chat, scope.user, now) != null
}

// a failed run left no history, so the retry says in the prompt what went wrong; usually a run that spent its
// whole budget researching and never delivered.
private const val PRIVATE_DELIVERY_HINT =
    "Everything this task sends goes to its owner's private chat, not to this chat. " +
            "That is already arranged, so send as usual."

private const val RETRY_HINT =
    "An earlier attempt at this task ended without delivering anything. " +
            "Get to the result faster this time: gather only what the task needs, then send it."

/** The turn a due task runs, with no incoming message behind it. */
internal fun scheduledAgentRequest(task: ScheduledTask, attempt: Int, chatProfile: ChatProfile): AgentRequest =
    AgentRequest(
        context = task.toRequestContext(chatProfile),
        prompt = scheduledTaskPrompt(task, attempt),
        conversationEntry = conversationEntry(task),
        replyPrivately = task.deliverPrivately,
    )

// history keeps the task as the user wrote it, without the retry hint.
private fun conversationEntry(task: ScheduledTask): String =
    scheduledTaskBlock(task.title, task.recurrence.display, task.prompt)

internal fun scheduledTaskPrompt(task: ScheduledTask, attempt: Int): String =
    scheduledTaskBlock(
        task.title,
        task.recurrence.display,
        buildString {
            appendLine("This is a scheduled task you set up earlier. Execute it now without asking for confirmation.")
            if (task.deliverPrivately) appendLine(PRIVATE_DELIVERY_HINT)
            append("Task: ").append(task.prompt)
            if (attempt > 1) append('\n').append(RETRY_HINT)
        },
    )
