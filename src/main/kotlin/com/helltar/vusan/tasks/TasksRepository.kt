package com.helltar.vusan.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ScheduledTasksTable
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.request.UserRef
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneId

class TasksRepository {

    suspend fun create(task: NewScheduledTask): Long = dbTransaction {
        ScheduledTasksTable
            .insertAndGetId {
                it[platform] = task.scope.platform
                it[userId] = task.scope.user.id
                it[chatId] = task.scope.chat.id
                it[prompt] = task.prompt
                it[title] = task.title
                it[recurrence] = task.recurrence.serialize()
                it[timezone] = task.timezone.id
                it[nextFireAt] = task.nextFireAt
                it[creatorMessageId] = task.creatorMessageId
                it[creatorThreadId] = task.creatorThreadId
                it[creatorUsername] = task.creatorUsername
                it[creatorDisplayName] = task.creatorDisplayName
                it[chatIsPrivate] = task.chatIsPrivate
                it[language] = task.language.code
                it[selfInitiated] = task.selfInitiated
                it[deliverPrivately] = task.deliverPrivately
            }.value
    }

    // user tasks and the bot's own follow-ups have separate limits, so the bot never eats the user's quota.
    // [selfInitiated] null counts both, the task menu's total.
    suspend fun countForUser(owner: UserRef, selfInitiated: Boolean? = null): Int = dbTransaction {
        var condition = ownedBy(owner)

        selfInitiated?.let { condition = condition and (ScheduledTasksTable.selfInitiated eq it) }

        ScheduledTasksTable.selectAll().where { condition }.count().toInt()
    }

    suspend fun listForUser(owner: UserRef, chat: ChatRef? = null): List<ScheduledTask> = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { taskCondition(owner, chat = chat) }
            .orderBy(ScheduledTasksTable.nextFireAt to SortOrder.ASC)
            .map { it.toScheduledTask() }
    }

    suspend fun findDue(now: Instant): List<ScheduledTask> = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { dueCondition(now) }
            .orderBy(ScheduledTasksTable.nextFireAt to SortOrder.ASC)
            .mapNotNull { it.dueTaskOrNull() }
    }

    /**
     * One task as it stands, only while still due. A tick reads all due tasks at once and fires them in
     * sequence, so an owner can still pause, retime or delete a task waiting behind a long fire.
     */
    suspend fun findDue(id: Long, now: Instant): ScheduledTask? = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { (ScheduledTasksTable.id eq id) and dueCondition(now) }
            .firstOrNull()
            ?.dueTaskOrNull()
    }

    suspend fun findForUser(owner: UserRef, id: Long, chat: ChatRef? = null): ScheduledTask? = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { taskCondition(owner, id, chat) }
            .firstOrNull()
            ?.toScheduledTask()
    }

    suspend fun pauseForUser(owner: UserRef, id: Long, chat: ChatRef? = null): Boolean = dbTransaction {
        ScheduledTasksTable
            .update({ taskCondition(owner, id, chat) }) {
                it[paused] = true
            } > 0
    }

    suspend fun resumeForUser(
        owner: UserRef,
        id: Long,
        nextFireAt: Instant,
        chat: ChatRef? = null,
    ): Boolean = dbTransaction {
        ScheduledTasksTable
            .update({ taskCondition(owner, id, chat) }) {
                it[ScheduledTasksTable.nextFireAt] = nextFireAt
                it[paused] = false
            } > 0
    }

    suspend fun editForUser(
        owner: UserRef,
        original: ScheduledTask,
        edited: ScheduledTask,
        chat: ChatRef? = null,
    ): Boolean = dbTransaction {
        require(original.id == edited.id) { "original and edited task ids must match" }

        // only changed columns are written: [original] was read before the edit, and writing it back whole
        // would undo a reschedule that landed in between, dragging a renamed task back to its fired slot.
        ScheduledTasksTable
            .update({ taskCondition(owner, original.id, chat) }) {
                if (original.prompt != edited.prompt)
                    it[prompt] = edited.prompt

                if (original.title != edited.title)
                    it[title] = edited.title

                if (original.recurrence != edited.recurrence)
                    it[recurrence] = edited.recurrence.serialize()

                if (original.timezone != edited.timezone)
                    it[timezone] = edited.timezone.id

                if (original.nextFireAt != edited.nextFireAt)
                    it[nextFireAt] = edited.nextFireAt

                if (original.deliverPrivately != edited.deliverPrivately)
                    it[deliverPrivately] = edited.deliverPrivately
            } > 0
    }

    /**
     * Pauses every task in one chat and returns the count, when the bot loses the right to post there.
     * Paused, not disabled, so the tasks stay in `/tasks` and their owners can resume them if the bot gets
     * back in.
     */
    suspend fun pauseAllInChat(chat: ChatRef): Int = dbTransaction {
        ScheduledTasksTable
            .update({ inChat(chat) and (ScheduledTasksTable.paused eq false) }) {
                it[paused] = true
            }
    }

    /**
     * Moves a fired task to [nextFireAt] and answers whether it was still the task that fired. [firedAt] is
     * the fire time the run started from: an owner who retimed the task during the turn meant it, so their
     * schedule is not overwritten.
     */
    suspend fun reschedule(id: Long, firedAt: Instant, nextFireAt: Instant): Boolean = dbTransaction {
        ScheduledTasksTable
            .update({ firedTaskCondition(id, firedAt) }) {
                it[ScheduledTasksTable.nextFireAt] = nextFireAt
            } > 0
    }

    /**
     * Removes a task that just fired for the last time, under the same condition as [reschedule]. Deleted
     * rather than disabled: `/tasks` never shows it and nothing reads it.
     */
    suspend fun deleteFinished(id: Long, firedAt: Instant): Boolean = dbTransaction {
        ScheduledTasksTable.deleteWhere { firedTaskCondition(id, firedAt) } > 0
    }

    suspend fun deleteForUser(owner: UserRef, id: Long, chat: ChatRef? = null): Boolean = dbTransaction {
        ScheduledTasksTable.deleteWhere { taskCondition(owner, id, chat) } > 0
    }

    private fun dueCondition(now: Instant): Op<Boolean> =
        (ScheduledTasksTable.paused eq false) and (ScheduledTasksTable.nextFireAt lessEq now)

    private fun firedTaskCondition(id: Long, firedAt: Instant): Op<Boolean> =
        (ScheduledTasksTable.id eq id) and (ScheduledTasksTable.nextFireAt eq firedAt)

    // a hand-edited row may hold a schedule nothing reads; firing on a guess would run at the wrong time, so
    // it stays due, is logged and skipped until fixed
    private fun ResultRow.dueTaskOrNull(): ScheduledTask? =
        runCatching { toScheduledTask() }
            .onFailure { log.error(it) { "task id=${this[ScheduledTasksTable.id].value} cannot be read and is skipped" } }
            .getOrNull()

    private fun ResultRow.toScheduledTask(): ScheduledTask {
        val tz = ZoneId.of(this[ScheduledTasksTable.timezone])

        return ScheduledTask(
            id = this[ScheduledTasksTable.id].value,
            scope =
                ConversationScope(
                    user = UserRef(this[ScheduledTasksTable.platform], this[ScheduledTasksTable.userId]),
                    chat = ChatRef(this[ScheduledTasksTable.platform], this[ScheduledTasksTable.chatId]),
                ),
            prompt = this[ScheduledTasksTable.prompt],
            title = this[ScheduledTasksTable.title],
            recurrence =
                checkNotNull(Recurrence.parse(this[ScheduledTasksTable.recurrence])) {
                    "unreadable recurrence [${this[ScheduledTasksTable.recurrence]}]"
                },
            timezone = tz,
            creatorMessageId = this[ScheduledTasksTable.creatorMessageId],
            creatorThreadId = this[ScheduledTasksTable.creatorThreadId],
            creatorUsername = this[ScheduledTasksTable.creatorUsername],
            creatorDisplayName = this[ScheduledTasksTable.creatorDisplayName],
            chatIsPrivate = this[ScheduledTasksTable.chatIsPrivate],
            selfInitiated = this[ScheduledTasksTable.selfInitiated],
            deliverPrivately = this[ScheduledTasksTable.deliverPrivately],
            nextFireAt = this[ScheduledTasksTable.nextFireAt],
            paused = this[ScheduledTasksTable.paused],
            language = Language.fromCode(this[ScheduledTasksTable.language]),
        )
    }

    private fun taskCondition(
        owner: UserRef,
        id: Long? = null,
        chat: ChatRef? = null,
    ): Op<Boolean> {
        var condition = ownedBy(owner)

        id?.let { condition = condition and (ScheduledTasksTable.id eq it) }
        chat?.let { condition = condition and inChat(it) }

        return condition
    }

    companion object {
        // caps against a model scheduling in a loop: a morning brief, a weekly digest and a few reminders
        // fit, a runaway turn does not.
        const val MAX_TASKS_PER_USER = 10
        const val MAX_FOLLOW_UPS_PER_USER = 3

        private val log = KotlinLogging.logger {}
    }
}

private fun ownedBy(owner: UserRef): Op<Boolean> =
    (ScheduledTasksTable.platform eq owner.platform) and (ScheduledTasksTable.userId eq owner.id)

private fun inChat(chat: ChatRef): Op<Boolean> =
    (ScheduledTasksTable.platform eq chat.platform) and (ScheduledTasksTable.chatId eq chat.id)
