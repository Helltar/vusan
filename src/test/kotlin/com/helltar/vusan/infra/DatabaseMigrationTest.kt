package com.helltar.vusan.infra

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class DatabaseMigrationTest {

    @Test
    fun `connect adds paused state to an existing scheduled tasks table`() {
        val tempDir = Files.createTempDirectory("vusan-database-migration-test")
        val dbPath = tempDir.resolve("vusan.db")

        try {
            createLegacyScheduledTasksTable(dbPath.toString())

            runBlocking {
                Db.connect(testConfig(dbPath.toString()))
                Db.disconnect()
            }

            val columns = scheduledTaskColumnDefaults(dbPath.toString())
            assertTrue("paused" in columns)
            assertEquals("0", columns["paused"])
        } finally {
            runBlocking { Db.disconnect() }
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun createLegacyScheduledTasksTable(dbPath: String) {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE scheduled_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id BIGINT NOT NULL,
                        chat_id BIGINT NOT NULL,
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

    private fun scheduledTaskColumnDefaults(dbPath: String): Map<String, String?> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(scheduled_tasks)").use { rows ->
                    buildMap {
                        while (rows.next())
                            put(rows.getString("name"), rows.getString("dflt_value"))
                    }
                }
            }
        }

    private fun testConfig(dbPath: String) =
        AppConfig(
            allowedIds = emptySet(),
            databasePath = dbPath,
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            llmProvider = LlmProviderConfig.Hosted(
                provider = HostedLlmProvider.OPENAI,
                apiKey = "test",
                model = "test",
                requestTimeout = 60.seconds
            ),
            maxMemoryPerScope = 10,
            maxTasksPerUser = 5,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            sandboxTimeoutSeconds = 30L,
            sandboxUrl = null,
            searxngUrl = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
