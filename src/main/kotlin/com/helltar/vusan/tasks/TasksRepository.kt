package com.helltar.vusan.tasks

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ScheduledTasksTable
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
                it[userId] = task.userId
                it[chatId] = task.chatId
                it[prompt] = task.prompt
                it[title] = task.title
                it[recurrence] = task.recurrence
                it[timezone] = task.timezone.id
                it[nextFireAt] = task.nextFireAt.atOffset(ZoneOffset.UTC)
                it[creatorMessageId] = task.creatorMessageId
                it[creatorUsername] = task.creatorUsername
                it[creatorDisplayName] = task.creatorDisplayName
                it[chatIsPrivate] = task.chatIsPrivate
            }.value
    }

    suspend fun countActiveByUser(userId: Long): Int = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { (ScheduledTasksTable.userId eq userId) and (ScheduledTasksTable.enabled eq true) }
            .count()
            .toInt()
    }

    suspend fun listActiveByUser(userId: Long): List<ScheduledTask> = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { (ScheduledTasksTable.userId eq userId) and (ScheduledTasksTable.enabled eq true) }
            .orderBy(ScheduledTasksTable.nextFireAt to SortOrder.ASC)
            .map { it.toScheduledTask() }
    }

    suspend fun findDue(now: Instant): List<ScheduledTask> = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { (ScheduledTasksTable.enabled eq true) and (ScheduledTasksTable.nextFireAt lessEq now.atOffset(ZoneOffset.UTC)) }
            .orderBy(ScheduledTasksTable.nextFireAt to SortOrder.ASC)
            .map { it.toScheduledTask() }
    }

    suspend fun findForUser(userId: Long, id: Long): ScheduledTask? = dbTransaction {
        ScheduledTasksTable
            .selectAll()
            .where { (ScheduledTasksTable.id eq id) and (ScheduledTasksTable.userId eq userId) }
            .firstOrNull()
            ?.toScheduledTask()
    }

    suspend fun reschedule(id: Long, nextFireAt: Instant) = dbTransaction {
        ScheduledTasksTable
            .update({ ScheduledTasksTable.id eq id }) {
                it[ScheduledTasksTable.nextFireAt] = nextFireAt.atOffset(ZoneOffset.UTC)
            }
    }

    suspend fun disable(id: Long) = dbTransaction {
        ScheduledTasksTable
            .update({ ScheduledTasksTable.id eq id }) {
                it[enabled] = false
            }
    }

    suspend fun delete(id: Long): Int = dbTransaction {
        ScheduledTasksTable.deleteWhere { ScheduledTasksTable.id eq id }
    }

    private fun ResultRow.toScheduledTask(): ScheduledTask {
        val tzRaw = this[ScheduledTasksTable.timezone]
        val tz = runCatching { ZoneId.of(tzRaw) }.getOrDefault(ZoneOffset.UTC)

        return ScheduledTask(
            id = this[ScheduledTasksTable.id].value,
            userId = this[ScheduledTasksTable.userId],
            chatId = this[ScheduledTasksTable.chatId],
            prompt = this[ScheduledTasksTable.prompt],
            title = this[ScheduledTasksTable.title],
            recurrence = this[ScheduledTasksTable.recurrence],
            timezone = tz,
            creatorMessageId = this[ScheduledTasksTable.creatorMessageId],
            creatorUsername = this[ScheduledTasksTable.creatorUsername],
            creatorDisplayName = this[ScheduledTasksTable.creatorDisplayName],
            chatIsPrivate = this[ScheduledTasksTable.chatIsPrivate],
            nextFireAt = this[ScheduledTasksTable.nextFireAt].toInstant(),
            createdAt = this[ScheduledTasksTable.createdAt].toInstant(),
            enabled = this[ScheduledTasksTable.enabled],
        )
    }
}
