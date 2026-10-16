package com.helltar.vusan.infra

import com.helltar.vusan.infra.tables.ChatDiaryTable
import com.helltar.vusan.infra.tables.TelegramChatStickerSetsTable
import com.helltar.vusan.infra.tables.TelegramChatStickersTable
import com.helltar.vusan.infra.tables.ConversationMessagesTable
import com.helltar.vusan.infra.tables.ConversationsTable
import com.helltar.vusan.infra.tables.GroupLogDigestsTable
import com.helltar.vusan.infra.tables.GroupLogTable
import com.helltar.vusan.infra.tables.LeaveAloneTable
import com.helltar.vusan.infra.tables.MemoryTable
import com.helltar.vusan.infra.tables.TelegramPendingUpdatesTable
import com.helltar.vusan.infra.tables.TelegramPollsTable
import com.helltar.vusan.infra.tables.ScheduledTasksTable
import com.helltar.vusan.infra.tables.TelegramStickerSetsTable
import com.helltar.vusan.infra.tables.TelegramStickersTable
import org.jetbrains.exposed.v1.core.Table

/**
 * The shape the code expects, and the number the database carries in its own `PRAGMA user_version` to
 * say it has that shape.
 *
 * Nothing is reconciled by comparing declarations: that could add a column but never rebuild a key, so
 * two installations running the same code could hold different schemas. A deployed database moves
 * forward through [steps] instead, written once as plain SQL; a database older than the first of them,
 * or newer than this build, is not opened at all.
 */
internal object Schema {

    /** Raise this by one for every schema change, together with the step that reaches it. */
    const val VERSION = 5

    /**
     * What moves a database from one version to the next: the statements under `N` take a database at
     * `N - 1` to `N`. Every step a start needs runs in one transaction, together with the stamps.
     *
     * A step is frozen once released — a database already moved by it is never moved again, so editing
     * one leaves deployments apart. It is SQL rather than the declarations in [tables], which describe
     * only the newest shape. `DatabaseMigrationTest` proves the steps land on what a fresh database
     * gets.
     */
    val steps: Map<Int, List<String>> =
        mapOf(
            5 to listOf(
                "CREATE TABLE leave_alone (platform VARCHAR(16) NOT NULL, chat_id VARCHAR(64) NOT NULL, " +
                        "user_id VARCHAR(64) NOT NULL, ends_at TEXT NOT NULL, " +
                        "CONSTRAINT pk_leave_alone PRIMARY KEY (platform, chat_id, user_id))",
            ),
        )

    /** The oldest version [steps] can move forward; anything before it was moved by hand. */
    val oldestStepped: Int = steps.keys.min() - 1

    val tables: List<Table> =
        listOf(
            ConversationMessagesTable,
            ConversationsTable,
            GroupLogTable,
            GroupLogDigestsTable,
            ChatDiaryTable,
            LeaveAloneTable,
            ScheduledTasksTable,
            MemoryTable,
            TelegramStickersTable,
            TelegramStickerSetsTable,
            TelegramChatStickerSetsTable,
            TelegramChatStickersTable,
            TelegramPendingUpdatesTable,
            TelegramPollsTable,
        )
}
