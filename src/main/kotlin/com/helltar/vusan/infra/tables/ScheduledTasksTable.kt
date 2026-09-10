package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object ScheduledTasksTable : LongIdTable("scheduled_tasks") {

    val platform = platformColumn()
    val userId = externalId("user_id")
    val chatId = externalId("chat_id")
    val title = varchar("title", 200).nullable()
    val prompt = text("prompt")
    val recurrence = varchar("recurrence", 100)
    val timezone = varchar("timezone", 64)
    val nextFireAt = timestamp("next_fire_at")

    // a task is paused by its owner, or by the bot losing the right to post where it fires. one that
    // has fired for the last time is deleted instead: nothing reads a task that will never run again.
    val paused = bool("paused").default(false)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    val selfInitiated = bool("self_initiated").default(false)
    val chatIsPrivate = bool("chat_is_private").default(true)
    val language = varchar("language", 16).nullable()
    val creatorMessageId = externalId("creator_message_id").nullable()
    val creatorThreadId = externalId("creator_thread_id").nullable()
    val creatorUsername = varchar("creator_username", 64).nullable()
    val creatorDisplayName = varchar("creator_display_name", 200).nullable()

    init {
        index(false, platform, userId)
        index(false, paused, nextFireAt)
    }
}
