package com.helltar.vusan.infra

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.testScope
import com.helltar.vusan.request.testUser
import com.helltar.vusan.tasks.NewScheduledTask
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tasks.TasksRepository
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class DatabaseMigrationTest {

    @Test
    fun `a fresh database is created at the current schema version`() = withTempDb { dbPath ->
        runBlocking {
            Db.connect(testConfig(dbPath))
            Db.disconnect()
        }

        assertEquals(Schema.VERSION, userVersion(dbPath))

        val tables = tableNames(dbPath)
        Schema.tables.forEach { assertTrue(it.tableName in tables, "tables were $tables") }
        assertTrue("summarized_through_message_id" in tableColumns(dbPath, "conversations"))
    }

    // the shape that has no version of its own: everything this project wrote before there were any.
    @Test
    fun `a database from before versioned schemas is refused, not reshaped`() = withTempDb { dbPath ->
        createLegacyScheduledTasksTable(dbPath)

        val failure = assertFailsWith<IllegalStateException> { runBlocking { Db.connect(testConfig(dbPath)) } }

        assertContains(failure.message.orEmpty(), "by hand")
        assertEquals(0, userVersion(dbPath))
        // nothing was created beside it, so the database is still exactly what the operator has to move
        assertEquals(setOf("scheduled_tasks"), tableNames(dbPath).filterNot { it.startsWith("sqlite_") }.toSet())
    }

    @Test
    fun `a database newer than this build is refused`() = withTempDb { dbPath ->
        runBlocking {
            Db.connect(testConfig(dbPath))
            Db.disconnect()
        }

        stampUserVersion(dbPath, Schema.VERSION + 1)

        val failure = assertFailsWith<IllegalStateException> { runBlocking { Db.connect(testConfig(dbPath)) } }

        assertContains(failure.message.orEmpty(), "newer")
        assertEquals(Schema.VERSION + 1, userVersion(dbPath))
    }

    @Test
    fun `a database older than the first step is refused, not reshaped`() = withTempDb { dbPath ->
        createSnapshot(dbPath, Schema.oldestStepped)
        stampUserVersion(dbPath, Schema.oldestStepped - 1)
        val before = shape(dbPath)

        val failure = assertFailsWith<IllegalStateException> { runBlocking { Db.connect(testConfig(dbPath)) } }

        assertContains(failure.message.orEmpty(), "by hand")
        assertEquals(Schema.oldestStepped - 1, userVersion(dbPath))
        assertEquals(before, shape(dbPath))
    }

    @Test
    fun `every version from the oldest stepped one up to this build has its step`() {
        assertEquals((Schema.oldestStepped + 1..Schema.VERSION).toList(), Schema.steps.keys.sorted())
    }

    // the guarantee the hand moves had by construction: a database moved forward ends in exactly the
    // shape a fresh one is created in. the oldest snapshot walks through every step there is.
    @Test
    fun `an older database moved forward has the shape of a fresh one and keeps its rows`() = withTempDb { dbPath ->
        val freshPath = dbPath.replace("vusan.db", "fresh.db")
        runBlocking {
            Db.connect(testConfig(freshPath))
            Db.disconnect()
        }

        createSnapshot(dbPath, Schema.oldestStepped)
        withStatement(dbPath) {
            it.execute("INSERT INTO memories (platform, scope, owner_id, content) VALUES ('TELEGRAM', 'USER', '100', 'likes tea')")
        }
        stampUserVersion(dbPath, Schema.oldestStepped)

        runBlocking {
            Db.connect(testConfig(dbPath))
            Db.disconnect()
        }

        assertEquals(Schema.VERSION, userVersion(dbPath))
        assertEquals(shape(freshPath), shape(dbPath))
        assertEquals(1, withStatement(dbPath) { st -> st.executeQuery("SELECT count(*) FROM memories").use { it.getInt(1) } })

        val snapshotPath = dbPath.replace("vusan.db", "snapshot.db")
        createSnapshot(snapshotPath, Schema.oldestStepped)

        val copy = "$dbPath.schema-${Schema.oldestStepped}"
        assertEquals(Schema.oldestStepped, userVersion(copy))
        assertEquals(shape(snapshotPath), shape(copy))
    }

    @Test
    fun `a step that fails leaves the database as it was found`() = withTempDb { dbPath ->
        createSnapshot(dbPath, Schema.oldestStepped)
        // the table the first step creates is already there, so that step cannot run
        val firstCreated = Schema.steps.getValue(Schema.oldestStepped + 1).firstNotNullOf(::createdTable)
        withStatement(dbPath) { it.execute("CREATE TABLE $firstCreated (x TEXT)") }
        stampUserVersion(dbPath, Schema.oldestStepped)
        val before = shape(dbPath)

        assertFailsWith<Exception> { runBlocking { Db.connect(testConfig(dbPath)) } }

        assertEquals(Schema.oldestStepped, userVersion(dbPath))
        assertEquals(before, shape(dbPath))
    }

    @Test
    fun `a database already at this version is opened as it stands`() = withTempDb { dbPath ->
        runBlocking {
            Db.connect(testConfig(dbPath))
            val tasks = TasksRepository()
            tasks.create(newTask())
            Db.disconnect()

            Db.connect(testConfig(dbPath))
            assertEquals(1, tasks.countForUser(testUser(100)))
            Db.disconnect()
        }

        assertEquals(Schema.VERSION, userVersion(dbPath))
    }

    private fun newTask() =
        NewScheduledTask(
            scope = testScope(userId = 100, chatId = -200),
            prompt = "post the digest",
            title = "digest",
            recurrence = Recurrence.Once,
            timezone = ZoneId.of("UTC"),
            nextFireAt = Instant.parse("2026-07-28T08:00:00Z"),
            creatorThreadId = null,
            creatorMessageId = "9007199254740993",
            creatorUsername = "tester",
            creatorDisplayName = "Test User",
            chatIsPrivate = false,
            language = Language.ENGLISH,
        )

    private fun withTempDb(block: (dbPath: String) -> Unit) {
        val tempDir = Files.createTempDirectory("vusan-database-schema-test")

        try {
            block(tempDir.resolve("vusan.db").toString())
        } finally {
            runBlocking { Db.disconnect() }
            tempDir.toFile().deleteRecursively()
        }
    }

    // the frozen schema a release of that version created, as `.schema` printed it
    private fun createSnapshot(dbPath: String, version: Int) {
        val ddl = checkNotNull(javaClass.getResource("/schema/$version.sql")) { "no snapshot of schema $version" }.readText()

        withStatement(dbPath) { statement ->
            ddl.split(";\n").filter { it.isNotBlank() }.forEach(statement::execute)
        }
    }

    private fun createdTable(sql: String): String? =
        Regex("""^CREATE TABLE (\w+)""").find(sql)?.groupValues?.get(1)

    // what a table is for the code reading and writing it: its columns, indices and foreign keys. not the
    // stored CREATE text, which SQLite keeps as it was typed — a table rebuilt by hand and renamed into
    // place reads `CREATE TABLE IF NOT EXISTS "name"` with the very same shape.
    private fun shape(dbPath: String): Map<String, List<String>> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            fun rows(sql: String, vararg columns: String): List<String> =
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        buildList { while (rs.next()) add(columns.joinToString("|") { rs.getString(it).orEmpty() }) }
                    }
                }

            rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", "name")
                .associateWith { table ->
                    val indices = rows("PRAGMA index_list(`$table`)", "name", "unique", "origin", "partial").map { index ->
                        val name = index.substringBefore('|')
                        index + "|" + rows("PRAGMA index_info(`$name`)", "name").joinToString(",")
                    }

                    rows("PRAGMA table_xinfo(`$table`)", "cid", "name", "type", "notnull", "dflt_value", "pk") +
                            indices.sorted() +
                            rows("PRAGMA foreign_key_list(`$table`)", "table", "from", "to")
                }
        }

    private fun userVersion(dbPath: String): Int =
        readPragma(dbPath, "user_version")

    private fun stampUserVersion(dbPath: String, version: Int) {
        withStatement(dbPath) { it.execute("PRAGMA user_version = $version") }
    }

    private fun readPragma(dbPath: String, pragma: String): Int =
        withStatement(dbPath) { statement ->
            statement.executeQuery("PRAGMA $pragma").use { if (it.next()) it.getInt(1) else 0 }
        }

    private fun <T> withStatement(dbPath: String, block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use(block)
        }

    // an older database of the current identity shape: the columns and indices added since are what
    // `connect` still reconciles. A schema change that has to rewrite a key is not reconciled at all —
    // the database is moved by hand — so nothing here stands in for one.
    private fun createLegacyScheduledTasksTable(dbPath: String) {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE scheduled_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        platform VARCHAR(16) NOT NULL,
                        user_id VARCHAR(64) NOT NULL,
                        chat_id VARCHAR(64) NOT NULL,
                        title VARCHAR(200),
                        prompt TEXT NOT NULL,
                        recurrence VARCHAR(100) NOT NULL,
                        timezone VARCHAR(64) NOT NULL,
                        next_fire_at TEXT NOT NULL,
                        enabled BOOLEAN NOT NULL DEFAULT 1,
                        created_at TEXT NOT NULL,
                        chat_is_private BOOLEAN NOT NULL DEFAULT 1,
                        language VARCHAR(16),
                        creator_message_id BIGINT,
                        creator_username VARCHAR(64),
                        creator_display_name VARCHAR(200)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun tableColumns(dbPath: String, table: String): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                    buildSet {
                        while (rows.next()) add(rows.getString("name"))
                    }
                }
            }
        }

    private fun tableNames(dbPath: String): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                    buildSet {
                        while (rows.next()) add(rows.getString("name"))
                    }
                }
            }
        }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxModelCalls = 70,
            accessPolicy = AccessPolicy(),
            appearance = null,
            databasePath = dbPath,
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            klipyApiKey = null,
            llmProvider = LlmProviderConfig.OpenAi(
                    apiKey = "test",
                model = "test",
                requestTimeout = 60.seconds,
            ),
            maxConcurrentTurns = 4,
            image = null,
            openAiStt = null,
            regolithToken = null,
            regolithUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null,
        )
}
