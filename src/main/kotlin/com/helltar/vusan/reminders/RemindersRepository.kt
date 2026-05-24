package com.helltar.vusan.reminders

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.RemindersTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class RemindersRepository {

    suspend fun create(reminder: NewReminder): Long = dbTransaction {
        RemindersTable
            .insertAndGetId {
                it[userId] = reminder.userId
                it[chatId] = reminder.chatId
                it[prompt] = reminder.prompt
                it[title] = reminder.title
                it[recurrence] = reminder.recurrence
                it[timezone] = reminder.timezone.id
                it[nextFireAt] = reminder.nextFireAt.atOffset(ZoneOffset.UTC)
                it[creatorMessageId] = reminder.creatorMessageId
                it[creatorUsername] = reminder.creatorUsername
                it[creatorDisplayName] = reminder.creatorDisplayName
                it[chatIsPrivate] = reminder.chatIsPrivate
            }.value
    }

    suspend fun countActiveByUser(userId: Long): Int = dbTransaction {
        RemindersTable
            .selectAll()
            .where { (RemindersTable.userId eq userId) and (RemindersTable.enabled eq true) }
            .count()
            .toInt()
    }

    suspend fun listActiveByUser(userId: Long): List<Reminder> = dbTransaction {
        RemindersTable
            .selectAll()
            .where { (RemindersTable.userId eq userId) and (RemindersTable.enabled eq true) }
            .orderBy(RemindersTable.nextFireAt to SortOrder.ASC)
            .map { it.toReminder() }
    }

    suspend fun findDue(now: Instant): List<Reminder> = dbTransaction {
        RemindersTable
            .selectAll()
            .where { (RemindersTable.enabled eq true) and (RemindersTable.nextFireAt lessEq now.atOffset(ZoneOffset.UTC)) }
            .orderBy(RemindersTable.nextFireAt to SortOrder.ASC)
            .map { it.toReminder() }
    }

    suspend fun findForUser(userId: Long, id: Long): Reminder? = dbTransaction {
        RemindersTable
            .selectAll()
            .where { (RemindersTable.id eq id) and (RemindersTable.userId eq userId) }
            .firstOrNull()
            ?.toReminder()
    }

    suspend fun reschedule(id: Long, nextFireAt: Instant) = dbTransaction {
        RemindersTable
            .update({ RemindersTable.id eq id }) {
                it[RemindersTable.nextFireAt] = nextFireAt.atOffset(ZoneOffset.UTC)
            }
    }

    suspend fun disable(id: Long) = dbTransaction {
        RemindersTable
            .update({ RemindersTable.id eq id }) {
                it[enabled] = false
            }
    }

    suspend fun delete(id: Long): Int = dbTransaction {
        RemindersTable.deleteWhere { RemindersTable.id eq id }
    }

    private fun ResultRow.toReminder(): Reminder {
        val tzRaw = this[RemindersTable.timezone]
        val tz = runCatching { ZoneId.of(tzRaw) }.getOrDefault(ZoneOffset.UTC)

        return Reminder(
            id = this[RemindersTable.id].value,
            userId = this[RemindersTable.userId],
            chatId = this[RemindersTable.chatId],
            prompt = this[RemindersTable.prompt],
            title = this[RemindersTable.title],
            recurrence = this[RemindersTable.recurrence],
            timezone = tz,
            creatorMessageId = this[RemindersTable.creatorMessageId],
            creatorUsername = this[RemindersTable.creatorUsername],
            creatorDisplayName = this[RemindersTable.creatorDisplayName],
            chatIsPrivate = this[RemindersTable.chatIsPrivate],
            nextFireAt = this[RemindersTable.nextFireAt].toInstant(),
            createdAt = this[RemindersTable.createdAt].toInstant(),
            enabled = this[RemindersTable.enabled],
        )
    }
}
