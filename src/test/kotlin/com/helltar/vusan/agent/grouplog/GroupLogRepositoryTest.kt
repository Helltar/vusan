package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class GroupLogRepositoryTest {

    private lateinit var tempDir: Path

    private val now: Instant = Instant.parse("2026-08-04T12:00:00Z")

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-chat-log-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `window read returns entries oldest first`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(messageId = 1L, text = "first", at = now.minusSeconds(300)))
        repository.record(entry(messageId = 2L, text = "second", at = now.minusSeconds(200)))
        repository.record(entry(messageId = 3L, text = "third", at = now.minusSeconds(100)))

        val entries = repository.readWindow(CHAT, now.minusSeconds(600), now, limit = 10)

        assertEquals(listOf("first", "second", "third"), entries.map { it.text })
    }

    @Test
    fun `window read keeps the newest entries when the limit bites`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repeat(5) { repository.record(entry(messageId = it + 1L, text = "m$it", at = now.minusSeconds(500L - it * 10))) }

        val entries = repository.readWindow(CHAT, now.minusSeconds(600), now, limit = 2)

        assertEquals(listOf("m3", "m4"), entries.map { it.text })
        assertEquals(5L, repository.countInWindow(CHAT, now.minusSeconds(600), now))
    }

    @Test
    fun `a redelivered update does not duplicate its row`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(messageId = 7L, text = "once", at = now))
        repository.record(entry(messageId = 7L, text = "once", at = now))

        assertEquals(1L, repository.countInWindow(CHAT, now.minusSeconds(60), now))
    }

    @Test
    fun `bot rows carry no message id and never collide`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repeat(3) {
            repository.record(
                GroupLogEntry(
                    chatId = CHAT,
                    messageId = null,
                    kind = GroupLogEntry.BOT_KIND,
                    sentAt = now,
                    text = "reply $it"
                )
            )
        }

        assertEquals(3L, repository.countInWindow(CHAT, now.minusSeconds(60), now))
    }

    @Test
    fun `author filter matches a username exactly and a display name by substring`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(1L, "hers", now, username = "Olena", name = "Olena Petrenko"))
        repository.record(entry(2L, "his", now, username = "serhii", name = "Serhii Koval"))

        val from = now.minusSeconds(60)

        assertEquals(listOf("hers"), repository.readWindow(CHAT, from, now, 10, author = "@olena").map { it.text })
        assertEquals(listOf("hers"), repository.readWindow(CHAT, from, now, 10, author = "petrenko").map { it.text })
        assertEquals(listOf("his"), repository.readWindow(CHAT, from, now, 10, author = "serhii").map { it.text })
        assertTrue(repository.readWindow(CHAT, from, now, 10, author = "nobody").isEmpty())
    }

    @Test
    fun `a value longer than its column is truncated instead of rejected`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(1L, "hi", now, username = "u".repeat(200), name = "n".repeat(500)))

        val stored = repository.readWindow(CHAT, now.minusSeconds(60), now, limit = 10).single()

        assertTrue(stored.senderUsername!!.length <= 64)
        assertTrue(stored.senderName!!.length <= 200)
    }

    @Test
    fun `recent drops the message that triggered the turn`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(1L, "earlier", now.minusSeconds(120)))
        repository.record(entry(2L, "the question", now))

        val entries = repository.recent(CHAT, limit = 10, since = now.minusSeconds(600), excludeMessageId = 2L)

        assertEquals(listOf("earlier"), entries.map { it.text })
    }

    @Test
    fun `clear removes the transcript and the cached digests`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(1L, "gone", now))
        repository.storeDigest(CHAT, LocalDate.of(2026, 8, 3), messageCount = 4, content = "a recap")

        repository.clear(CHAT)

        assertEquals(0L, repository.countInWindow(CHAT, now.minusSeconds(600), now))
        assertNull(repository.digestFor(CHAT, LocalDate.of(2026, 8, 3)))
    }

    @Test
    fun `retention drops rows past the cutoff once pruning kicks in`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig(retentionDays = 1))
        val stale = Instant.now().minus(10, ChronoUnit.DAYS)

        repeat(60) { repository.record(entry(messageId = it + 1L, text = "old$it", at = stale)) }

        val fresh = Instant.now()

        // pruning is amortized over inserts, so it takes a run of them to trigger.
        repeat(500) { repository.record(entry(messageId = 1_000L + it, text = "new$it", at = fresh)) }

        assertEquals(0L, repository.countInWindow(CHAT, stale.minusSeconds(60), stale.plusSeconds(60)))
        assertEquals(500L, repository.countInWindow(CHAT, fresh.minusSeconds(60), fresh.plusSeconds(60)))
    }

    @Test
    fun `the per-chat row cap trims the oldest rows`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig(maxMessagesPerChat = 100))
        val base = Instant.now().minusSeconds(1_000)

        repeat(500) { repository.record(entry(messageId = it + 1L, text = "m$it", at = base.plusSeconds(it.toLong()))) }

        val remaining = repository.countInWindow(CHAT, base.minusSeconds(60), Instant.now())

        assertTrue(remaining <= 100L, "expected the cap to hold, got $remaining rows")

        val oldest = repository.readWindow(CHAT, base.minusSeconds(60), Instant.now(), limit = 1).single()

        assertTrue(oldest.text!!.removePrefix("m").toInt() >= 400)
    }

    @Test
    fun `a stored digest round-trips and is replaced on rewrite`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())
        val day = LocalDate.of(2026, 8, 3)

        repository.storeDigest(CHAT, day, messageCount = 10, content = "first take")
        assertEquals("first take", repository.digestFor(CHAT, day))

        repository.storeDigest(CHAT, day, messageCount = 12, content = "second take")
        assertEquals("second take", repository.digestFor(CHAT, day))
    }

    @Test
    fun `an edit rewrites what the transcript quotes`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(messageId = 1L, text = "wehter in kyiv", at = now.minusSeconds(100)))

        assertTrue(repository.recordEdit(entry(messageId = 1L, text = "weather in kyiv", at = now.minusSeconds(100))))

        val entries = repository.readWindow(CHAT, now.minusSeconds(600), now, limit = 10)

        assertEquals(listOf("weather in kyiv"), entries.map { it.text })
    }

    @Test
    fun `an edit drops the cached digest of the day it belongs to`() = runBlocking {
        // the day may already be closed, and nothing else would ever invalidate a stored digest
        val repository = GroupLogRepository(GroupLogConfig())
        val sentAt = now.minusSeconds(100)
        val day = LocalDate.ofInstant(sentAt, ZoneId.systemDefault())

        repository.record(entry(messageId = 1L, text = "before", at = sentAt))
        repository.storeDigest(CHAT, day, messageCount = 4, content = "a recap quoting before")

        repository.recordEdit(entry(messageId = 1L, text = "after", at = sentAt))

        assertNull(repository.digestFor(CHAT, day))
    }

    @Test
    fun `an edit of a message the log never saw is not backfilled`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        assertFalse(repository.recordEdit(entry(messageId = 7L, text = "never recorded", at = now)))
        assertEquals(0L, repository.countInWindow(CHAT, now.minusSeconds(600), now))
    }

    @Test
    fun `an edit leaves the original send time alone`() = runBlocking {
        // sent_at drives the day a message is filed under; an edit must not move it to the edit's own day
        val repository = GroupLogRepository(GroupLogConfig())
        val sentAt = now.minusSeconds(500)

        repository.record(entry(messageId = 1L, text = "before", at = sentAt))
        repository.recordEdit(entry(messageId = 1L, text = "after", at = now))

        assertEquals(1L, repository.countInWindow(CHAT, sentAt.minusSeconds(1), sentAt.plusSeconds(1)))
    }

    private fun entry(
        messageId: Long,
        text: String,
        at: Instant,
        username: String? = "olena",
        name: String? = "Olena Petrenko"
    ) =
        GroupLogEntry(
            chatId = CHAT,
            messageId = messageId,
            kind = "text",
            sentAt = at,
            senderId = 1L,
            senderUsername = username,
            senderName = name,
            text = text
        )

    private companion object {
        const val CHAT = -100L
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            allowedIds = emptySet(),
            appearance = null,
            databasePath = dbPath,
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            llmProvider =
                LlmProviderConfig.Hosted(
                    provider = HostedLlmProvider.OPENAI,
                    apiKey = "test",
                    model = "test",
                    requestTimeout = 60.seconds
                ),
            maxFollowUpsPerUser = 3,
            maxMemoryPerScope = 10,
            maxTasksPerUser = 5,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            workspaceMaxTimeoutSeconds = 600L,
            workspaceToken = null,
            workspaceUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
