package com.helltar.vusan.infra

import com.helltar.vusan.infra.tables.ChatDiaryLookBackTable
import com.helltar.vusan.infra.tables.ChatDiaryTable
import com.helltar.vusan.infra.tables.TelegramChatStickerSetsTable
import com.helltar.vusan.infra.tables.TelegramChatStickersTable
import com.helltar.vusan.infra.tables.ConversationMessagesTable
import com.helltar.vusan.infra.tables.ConversationsTable
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
 * The shape the code expects, and the number the database carries in `PRAGMA user_version` to say it has it.
 *
 * Nothing is reconciled from declarations, which could add a column but never rebuild a key, so two
 * installations could diverge. A deployed database moves forward through [steps], plain SQL written once; one
 * older than the first step or newer than this build is not opened.
 */
internal object Schema {

    /** Raise by one for every schema change, together with the step that reaches it. */
    const val VERSION = 8

    /**
     * The steps between versions: the statements under `N` take a database from `N - 1` to `N`. Every step a
     * start needs runs in one transaction with the stamps.
     *
     * A released step is frozen: a database it already moved is never moved again, so editing one splits
     * deployments. SQL rather than [tables], which describe only the newest shape. `DatabaseMigrationTest`
     * proves the steps land on what a fresh database gets.
     */
    val steps: Map<Int, List<String>> =
        mapOf(
            5 to listOf(
                "CREATE TABLE leave_alone (platform VARCHAR(16) NOT NULL, chat_id VARCHAR(64) NOT NULL, " +
                        "user_id VARCHAR(64) NOT NULL, ends_at TEXT NOT NULL, " +
                        "CONSTRAINT pk_leave_alone PRIMARY KEY (platform, chat_id, user_id))",
            ),
            6 to listOf(
                "CREATE TABLE chat_diary_look_back (platform VARCHAR(16) NOT NULL, chat_id VARCHAR(64) NOT NULL, " +
                        "period VARCHAR(8) NOT NULL, first_day VARCHAR(10) NOT NULL, content TEXT NOT NULL, " +
                        "CONSTRAINT pk_chat_diary_look_back PRIMARY KEY (platform, chat_id, period, first_day))",
            ),
            7 to listOf(
                "UPDATE scheduled_tasks SET language = CASE language WHEN 'ENGLISH' THEN 'en' " +
                        "WHEN 'UKRAINIAN' THEN 'uk' WHEN 'RUSSIAN' THEN 'ru' WHEN 'SPANISH' THEN 'es' END",
            ),
            8 to listOf(
                "DROP TABLE group_log_digests",
            ),
        )

    /** The oldest version [steps] can move forward; anything older was moved by hand. */
    val oldestStepped: Int = steps.keys.min() - 1

    val tables: List<Table> =
        listOf(
            ConversationMessagesTable,
            ConversationsTable,
            GroupLogTable,
            ChatDiaryTable,
            ChatDiaryLookBackTable,
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
