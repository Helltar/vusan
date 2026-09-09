package com.helltar.vusan.tasks

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
import java.time.ZoneOffset

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
                it[language] = task.language.name
                it[selfInitiated] = task.selfInitiated
            }.value
    }

    // user-requested tasks and the bot's own follow-ups are counted against separate limits, so the
    // bot can never fill up the quota the user needs for their own reminders. [selfInitiated] null
    // counts both, which is what the task menu shows as the user's overall total.
    suspend fun countEnabledByUser(owner: UserRef, selfInitiated: Boolean? = null): Int = dbTransaction {
        var condition = ownedBy(owner) and (ScheduledTasksTable.enabled eq true)

        selfInitiated?.let { condition = condition and (ScheduledTasksTable.selfInitiated eq it) }

        ScheduledTasksTable.selectAll().where { condition }.count().toInt()
    }

    suspend fun listEnabledByUser(owner: UserRef, chat: ChatRef? = null): List<ScheduledTask> = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { enabledTaskCondition(owner, chat = chat) }
            .orderBy(ScheduledTasksTable.nextFireAt to SortOrder.ASC)
            .map { it.toScheduledTask() }
    }

    suspend fun findDue(now: Instant): List<ScheduledTask> = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where {
                (ScheduledTasksTable.enabled eq true) and
                        (ScheduledTasksTable.paused eq false) and
                        (ScheduledTasksTable.nextFireAt lessEq now)
            }
            .orderBy(ScheduledTasksTable.nextFireAt to SortOrder.ASC)
            .map { it.toScheduledTask() }
    }

    suspend fun findEnabledForUser(owner: UserRef, id: Long, chat: ChatRef? = null): ScheduledTask? = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { enabledTaskCondition(owner, id, chat) }
            .firstOrNull()
            ?.toScheduledTask()
    }

    suspend fun pauseForUser(owner: UserRef, id: Long, chat: ChatRef? = null): Boolean = dbTransaction {
        ScheduledTasksTable
            .update({ enabledTaskCondition(owner, id, chat) }) {
                it[paused] = true
            } > 0
    }

    suspend fun resumeForUser(
        owner: UserRef,
        id: Long,
        nextFireAt: Instant,
        chat: ChatRef? = null
    ): Boolean = dbTransaction {
        ScheduledTasksTable
            .update({ enabledTaskCondition(owner, id, chat) }) {
                it[ScheduledTasksTable.nextFireAt] = nextFireAt
                it[paused] = false
            } > 0
    }

    suspend fun editEnabledForUser(
        owner: UserRef,
        original: ScheduledTask,
        edited: ScheduledTask,
        chat: ChatRef? = null
    ): Boolean = dbTransaction {
        require(original.id == edited.id) { "original and edited task ids must match" }

        // only the columns the caller actually changed are written. [original] is read before the edit, so
        // writing it back wholesale would silently undo a TaskScheduler reschedule that landed in between —
        // renaming a task would drag its fire time back to the already-fired slot.
        ScheduledTasksTable
            .update({ enabledTaskCondition(owner, original.id, chat) }) {
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
            } > 0
    }

    /**
     * Pauses every task scheduled in one chat and answers how many that was. Used when the bot loses the
     * right to post there: pausing rather than disabling keeps the tasks listed in `/tasks`, so their
     * owners can resume them if the bot gets back in.
     */
    suspend fun pauseAllInChat(chat: ChatRef): Int = dbTransaction {
        ScheduledTasksTable
            .update({
                inChat(chat) and
                        (ScheduledTasksTable.enabled eq true) and
                        (ScheduledTasksTable.paused eq false)
            }) {
                it[paused] = true
            }
    }

    suspend fun reschedule(id: Long, nextFireAt: Instant) = dbTransaction {
        ScheduledTasksTable
            .update({ ScheduledTasksTable.id eq id }) {
                it[ScheduledTasksTable.nextFireAt] = nextFireAt
            }
    }

    suspend fun disable(id: Long) = dbTransaction {
        ScheduledTasksTable
            .update({ ScheduledTasksTable.id eq id }) {
                it[enabled] = false
            }
    }

    suspend fun deleteEnabledForUser(owner: UserRef, id: Long, chat: ChatRef? = null): Boolean = dbTransaction {
        ScheduledTasksTable.deleteWhere { enabledTaskCondition(owner, id, chat) } > 0
    }

    private fun ResultRow.toScheduledTask(): ScheduledTask {
        val tzRaw = this[ScheduledTasksTable.timezone]
        val tz = runCatching { ZoneId.of(tzRaw) }.getOrDefault(ZoneOffset.UTC)

        return ScheduledTask(
            id = this[ScheduledTasksTable.id].value,
            scope =
                ConversationScope(
                    user = UserRef(this[ScheduledTasksTable.platform], this[ScheduledTasksTable.userId]),
                    chat = ChatRef(this[ScheduledTasksTable.platform], this[ScheduledTasksTable.chatId])
                ),
            prompt = this[ScheduledTasksTable.prompt],
            title = this[ScheduledTasksTable.title],
            recurrence = Recurrence.parse(this[ScheduledTasksTable.recurrence]) ?: Recurrence.Once,
            timezone = tz,
            creatorMessageId = this[ScheduledTasksTable.creatorMessageId],
            creatorThreadId = this[ScheduledTasksTable.creatorThreadId],
            creatorUsername = this[ScheduledTasksTable.creatorUsername],
            creatorDisplayName = this[ScheduledTasksTable.creatorDisplayName],
            chatIsPrivate = this[ScheduledTasksTable.chatIsPrivate],
            selfInitiated = this[ScheduledTasksTable.selfInitiated],
            nextFireAt = this[ScheduledTasksTable.nextFireAt],
            createdAt = this[ScheduledTasksTable.createdAt],
            enabled = this[ScheduledTasksTable.enabled],
            paused = this[ScheduledTasksTable.paused],
            language =
                this[ScheduledTasksTable.language]?.let { runCatching { Language.valueOf(it) }.getOrNull() }
                    ?: Language.DEFAULT
        )
    }

    private fun enabledTaskCondition(
        owner: UserRef,
        id: Long? = null,
        chat: ChatRef? = null
    ): Op<Boolean> {
        var condition = ownedBy(owner) and (ScheduledTasksTable.enabled eq true)

        id?.let { condition = condition and (ScheduledTasksTable.id eq it) }
        chat?.let { condition = condition and inChat(it) }
        return condition
    }
}

private fun ownedBy(owner: UserRef): Op<Boolean> =
    (ScheduledTasksTable.platform eq owner.platform) and (ScheduledTasksTable.userId eq owner.id)

private fun inChat(chat: ChatRef): Op<Boolean> =
    (ScheduledTasksTable.platform eq chat.platform) and (ScheduledTasksTable.chatId eq chat.id)
