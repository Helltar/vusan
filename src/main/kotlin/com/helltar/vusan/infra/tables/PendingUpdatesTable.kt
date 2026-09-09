package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * Updates Telegram has handed over but the bot has not yet begun to handle.
 *
 * A row exists only for that gap. The polling session confirms an update to Telegram by asking for a
 * higher offset on its *next* request, so an update the bot is still working on is already forgotten
 * upstream: without this table a restart in the middle loses it with nothing to replay.
 */
object PendingUpdatesTable : Table("pending_updates") {

    /** Telegram's own update id, which is what makes a replay and a redelivery the same row. */
    val updateId = long("update_id")

    /** The update exactly as Telegram sent it — the Bot API JSON, re-serialized unchanged. */
    val payload = text("payload")

    val receivedAt = timestamp("received_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(updateId)

    init {
        index(false, receivedAt)
    }
}
