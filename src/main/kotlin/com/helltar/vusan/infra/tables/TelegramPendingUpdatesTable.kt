package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * Updates telegram handed over that the bot has not begun to handle. The session confirms an update by asking
 * for a higher offset on its *next* request, so an update still being worked on is already forgotten
 * upstream; without this table a restart loses it.
 */
object TelegramPendingUpdatesTable : Table("telegram_pending_updates") {

    /** Telegram's update id, which makes a replay and a redelivery the same row. */
    val updateId = long("update_id")

    /** The update as telegram sent it, the Bot API JSON re-serialized unchanged. */
    val payload = text("payload")

    val receivedAt = timestamp("received_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(updateId)

    init {
        index(false, receivedAt)
    }
}
