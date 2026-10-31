package com.helltar.vusan.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.TelegramPendingUpdatesTable
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
 * The gap between telegram handing an update over and the bot starting on it.
 *
 * The polling session confirms updates by asking for a higher offset on its *next* request, so a running
 * turn's update is already gone upstream and a restart loses it. Every update is written here before the poll
 * callback returns, keeping it out of that next request until safe, and removed when the dispatch loop picks
 * it up.
 *
 * Deliberately so: only work never begun survives a restart. A started turn may have posted, spent tokens or
 * written history, and a replay would repeat all three, so the person sees a turn that went quiet rather than
 * one that happened twice.
 */
internal class UpdateSpool(private val retention: Duration) {

    // the library's mapper settings live on the classes (`@Jacksonized`, NON_NULL, unknown properties
    // ignored), so a default mapper reproduces the Bot API wire format.
    private val mapper = ObjectMapper()

    /** Persists a polled batch, best-effort: losing durability beats not answering now. */
    suspend fun record(updates: List<Update>) {
        if (updates.isEmpty()) return

        runCatching {
            val rows = updates.map { it.updateId.toLong() to mapper.writeValueAsString(it) }

            dbTransaction {
                // a replay racing telegram's own redelivery would collide here, and the row on disk is the
                // same update.
                TelegramPendingUpdatesTable.batchInsert(rows, ignore = true) { (id, json) ->
                    this[TelegramPendingUpdatesTable.updateId] = id
                    this[TelegramPendingUpdatesTable.payload] = json
                }
            }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to spool ${updates.size} update(s); a restart now would lose them" }
        }
    }

    /** The dispatch loop took this update; a restart no longer owes it. */
    suspend fun settle(updateId: Int) {
        runCatching {
            dbTransaction {
                TelegramPendingUpdatesTable.deleteWhere { TelegramPendingUpdatesTable.updateId eq updateId.toLong() }
            }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to drop spooled update=$updateId; it may be handled twice after a restart" }
        }
    }

    /**
     * Everything still owed at startup, oldest first, with the rest of the table cleared. Anything past
     * [retention] is dropped: answering a message from hours ago reads worse than never answering.
     */
    suspend fun drain(now: Instant = Instant.now()): List<Update> =
        runCatching {
            val cutoff = now.minusMillis(retention.inWholeMilliseconds)

            val stale =
                dbTransaction {
                    TelegramPendingUpdatesTable.deleteWhere { TelegramPendingUpdatesTable.receivedAt less cutoff }
                }

            if (stale > 0) log.warn { "dropped $stale update(s) older than $retention instead of answering late" }

            val pending =
                dbTransaction {
                    TelegramPendingUpdatesTable
                        .selectAll()
                        .orderBy(TelegramPendingUpdatesTable.updateId to SortOrder.ASC)
                        .map { it[TelegramPendingUpdatesTable.updateId] to it[TelegramPendingUpdatesTable.payload] }
                }

            pending.mapNotNull { (id, json) ->
                runCatching { mapper.readValue(json, Update::class.java) }
                    .getOrElse { error ->
                        // a payload this build cannot read is no reason to block startup; `settle` below
                        // removes it for good.
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

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
