package com.helltar.vusan.infra

import com.helltar.vusan.infra.tables.ChatStickerSetsTable
import com.helltar.vusan.infra.tables.ChatStickersTable
import com.helltar.vusan.infra.tables.ConversationMessagesTable
import com.helltar.vusan.infra.tables.ConversationsTable
import com.helltar.vusan.infra.tables.GroupLogDigestsTable
import com.helltar.vusan.infra.tables.GroupLogTable
import com.helltar.vusan.infra.tables.MemoryTable
import com.helltar.vusan.infra.tables.PendingUpdatesTable
import com.helltar.vusan.infra.tables.PollsTable
import com.helltar.vusan.infra.tables.ScheduledTasksTable
import com.helltar.vusan.infra.tables.StickerSetsTable
import com.helltar.vusan.infra.tables.StickersTable
import com.helltar.vusan.infra.tables.TokenUsageTable
import com.helltar.vusan.infra.tables.TokenUserSpendTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/** One step of the schema's history: what turns the version before [to] into [to]. */
internal class Migration(val to: Int, val apply: JdbcTransaction.() -> Unit)

/**
 * The shape the code expects, and the number the database carries in its own `PRAGMA user_version` to
 * say it has that shape.
 *
 * Nothing is reconciled by comparing declarations any more. Adding a column that way worked; changing a
 * key, a type or a table's name never did, and the two databases would then differ while the code they
 * ran was the same. A change is a [Migration] instead, and a database whose version this build does not
 * know is not opened at all. `docs/database.md` says how one is moved across a change by hand.
 */
internal object Schema {

    /** Raise this by one for every schema change, and add the [Migration] that reaches it. */
    const val VERSION = 1

    val tables: List<Table> =
        listOf(
            ConversationMessagesTable,
            ConversationsTable,
            GroupLogTable,
            GroupLogDigestsTable,
            ScheduledTasksTable,
            MemoryTable,
            StickersTable,
            StickerSetsTable,
            ChatStickerSetsTable,
            ChatStickersTable,
            TokenUsageTable,
            TokenUserSpendTable,
            PendingUpdatesTable,
            PollsTable
        )

    /**
     * A step per version above the first, applied in order. They run inside the connect transaction, so
     * a step that throws leaves the database at the version it had.
     *
     * Version 1 is the baseline: it is created whole from [tables], and a database written before there
     * were versions at all is moved by hand rather than guessed at.
     */
    val migrations: List<Migration> = emptyList()
}
