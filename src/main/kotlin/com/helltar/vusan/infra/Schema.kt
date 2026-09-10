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

/**
 * The shape the code expects, and the number the database carries in its own `PRAGMA user_version` to
 * say it has that shape.
 *
 * Nothing is reconciled by comparing declarations, and nothing is migrated in code. Reconciling could add
 * a column but never rebuild a key, so two installations running the same code could hold different
 * schemas; migrations are not worth writing before 1.0, when a change may rewrite anything. So a database
 * is moved by hand — `docs/database.md` carries the recipe — and this number is how the code can tell that
 * it has been: a database whose version this build does not know is not opened at all.
 */
internal object Schema {

    /** Raise this by one for every schema change, and move deployed databases by hand to match. */
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
}
