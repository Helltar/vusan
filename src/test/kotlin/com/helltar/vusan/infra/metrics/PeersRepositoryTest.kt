package com.helltar.vusan.infra.metrics

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.PeersTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PeersRepositoryTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-peers-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `touch records a chat row and a user row`() = runBlocking {
        val repo = PeersRepository()

        repo.touch(chatId = -100L, chatIsPrivate = false, userId = 1L)

        val stats = repo.stats()
        assertEquals(1, stats.chatsKnown)
        assertEquals(1, stats.usersKnown)
    }

    @Test
    fun `private chat where chat id equals user id yields one chat and one user`() = runBlocking {
        val repo = PeersRepository()

        repo.touch(chatId = 42L, chatIsPrivate = true, userId = 42L)

        val stats = repo.stats()
        assertEquals(1, stats.chatsKnown)
        assertEquals(1, stats.usersKnown)
    }

    @Test
    fun `repeated touch updates lastSeen but keeps firstSeen and row count`() = runBlocking {
        val repo = PeersRepository()

        // SQLite stores timestamps with millisecond precision, so sub-millisecond parts would not roundtrip.
        val second = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val first = second.minus(3, ChronoUnit.DAYS)

        repo.touch(chatId = -100L, chatIsPrivate = false, userId = 1L, now = first)
        repo.touch(chatId = -100L, chatIsPrivate = false, userId = 1L, now = second)

        val rows =
            dbTransaction {
                PeersTable.selectAll().map {
                    Triple(it[PeersTable.kind], it[PeersTable.firstSeen], it[PeersTable.lastSeen])
                }
            }

        assertEquals(2, rows.size)

        rows.forEach { (_, firstSeen, lastSeen) ->
            assertEquals(first, firstSeen)
            assertEquals(second, lastSeen)
        }
    }

    @Test
    fun `stats counts active peers by lastSeen window`() = runBlocking {
        val repo = PeersRepository()
        val now = Instant.now()

        // active within the last 24h
        repo.touch(chatId = -1L, chatIsPrivate = false, userId = 1L, now = now.minus(1, ChronoUnit.HOURS))
        // active within the last 7d, but not 24h
        repo.touch(chatId = -2L, chatIsPrivate = false, userId = 2L, now = now.minus(3, ChronoUnit.DAYS))
        // inactive for both windows
        repo.touch(chatId = -3L, chatIsPrivate = false, userId = 3L, now = now.minus(30, ChronoUnit.DAYS))

        val stats = repo.stats(now)

        assertEquals(3, stats.chatsKnown)
        assertEquals(3, stats.usersKnown)
        assertEquals(1, stats.chatsActive24h)
        assertEquals(2, stats.chatsActive7d)
        assertEquals(1, stats.usersActive24h)
        assertEquals(2, stats.usersActive7d)
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
            metricsPort = null,
            openAiStt = null,
            sandboxTimeoutSeconds = 30L,
            sandboxUrl = null,
            systemPrompt = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
