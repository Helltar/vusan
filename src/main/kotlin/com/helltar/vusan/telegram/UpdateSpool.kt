package com.helltar.vusan.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.PendingUpdatesTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import kotlin.time.Duration
import org.telegram.telegrambots.meta.api.objects.Update

/**
 * The gap between Telegram handing an update over and the bot starting to handle it.
 *
 * The polling session confirms updates by asking for a higher offset on its *next* request, so by the
 * time a turn is running the update is already gone upstream and a restart loses it. Every update is
 * written here before the poll callback returns — which is what keeps it out of that next request
 * until it is safe — and removed the moment the dispatch loop picks it up.
 *
 * That boundary is deliberate. What survives a restart is work that was never begun; a turn that had
 * already started may have put messages in the chat, spent tokens, or written history, and replaying
 * it would repeat all three. Those are dropped, and the person sees a turn that went quiet rather
 * than one that happened twice.
 */
internal class UpdateSpool(private val retention: Duration) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    // the library's own mapper settings are on the classes themselves (`@Jacksonized`, NON_NULL,
    // unknown properties ignored), so a default mapper reproduces the Bot API wire format exactly.
    private val mapper = ObjectMapper()

    /**
     * Persist a polled batch. Best-effort by design: a spool that cannot be written must not stop the
     * bot from answering, since losing durability is strictly better than dropping the update now.
     */
    suspend fun record(updates: List<Update>) {
        if (updates.isEmpty()) return

        runCatching {
            val rows = updates.map { it.updateId.toLong() to mapper.writeValueAsString(it) }

            dbTransaction {
                // a replay racing Telegram's own redelivery of the same update would collide here, and
                // the row already on disk is the same update either way.
                PendingUpdatesTable.batchInsert(rows, ignore = true) { (id, json) ->
                    this[PendingUpdatesTable.updateId] = id
                    this[PendingUpdatesTable.payload] = json
                }
            }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to spool ${updates.size} update(s); a restart now would lose them" }
        }
    }

    /** The dispatch loop has taken this update; it is no longer work that a restart owes anyone. */
    suspend fun settle(updateId: Int) {
        runCatching {
            dbTransaction {
                PendingUpdatesTable.deleteWhere { PendingUpdatesTable.updateId eq updateId.toLong() }
            }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to drop spooled update=$updateId; it may be handled twice after a restart" }
        }
    }

    /**
     * Everything still owed at startup, oldest first, with the rest of the table cleared.
     *
     * Anything past [retention] is dropped unanswered: replaying a message from hours ago answers a
     * conversation that has moved on, which reads worse than never having answered it.
     */
    suspend fun drain(now: Instant = Instant.now()): List<Update> =
        runCatching {
            val cutoff = now.minusMillis(retention.inWholeMilliseconds)

            val stale =
                dbTransaction {
                    PendingUpdatesTable.deleteWhere { PendingUpdatesTable.receivedAt less cutoff }
                }

            if (stale > 0) log.warn { "dropped $stale update(s) older than $retention instead of answering late" }

            val pending =
                dbTransaction {
                    PendingUpdatesTable
                        .selectAll()
                        .orderBy(PendingUpdatesTable.updateId to SortOrder.ASC)
                        .map { it[PendingUpdatesTable.updateId] to it[PendingUpdatesTable.payload] }
                }

            pending.mapNotNull { (id, json) ->
                runCatching { mapper.readValue(json, Update::class.java) }
                    .getOrElse { error ->
                        // a payload this build can no longer read is not worth blocking startup over,
                        // and `settle` below takes it out of the way for good.
                        log.warn(error) { "spooled update=$id could not be read back; dropping it" }
                        settle(id.toInt())
                        null
                    }
            }.also { if (it.isNotEmpty()) log.info { "replaying ${it.size} update(s) left over from the last run" } }
        }.getOrElse {
            it.rethrowIfCancellation()
            log.error(it) { "failed to read the update spool; starting without replaying anything" }
            emptyList()
        }
}
