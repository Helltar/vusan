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
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.testChat
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

        repository.record(entry(messageId = "1", text = "first", at = now.minusSeconds(300)))
        repository.record(entry(messageId = "2", text = "second", at = now.minusSeconds(200)))
        repository.record(entry(messageId = "3", text = "third", at = now.minusSeconds(100)))

        val entries = repository.readWindow(CHAT, now.minusSeconds(600), now, limit = 10)

        assertEquals(listOf("first", "second", "third"), entries.map { it.text })
    }

    @Test
    fun `window read keeps the newest entries when the limit bites`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repeat(5) { repository.record(entry(messageId = "${it + 1}", text = "m$it", at = now.minusSeconds(500L - it * 10))) }

        val entries = repository.readWindow(CHAT, now.minusSeconds(600), now, limit = 2)

        assertEquals(listOf("m3", "m4"), entries.map { it.text })
        assertEquals(5L, repository.countInWindow(CHAT, now.minusSeconds(600), now))
    }

    @Test
    fun `a redelivered update does not duplicate its row`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(messageId = "7", text = "once", at = now))
        repository.record(entry(messageId = "7", text = "once", at = now))

        assertEquals(1L, repository.countInWindow(CHAT, now.minusSeconds(60), now))
    }

    @Test
    fun `bot rows carry no message id and never collide`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repeat(3) {
            repository.record(
                GroupLogEntry(
                    chat = CHAT,
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

        repository.record(entry("1", "hers", now, username = "Olena", name = "Olena Petrenko"))
        repository.record(entry("2", "his", now, username = "serhii", name = "Serhii Koval"))

        val from = now.minusSeconds(60)

        assertEquals(listOf("hers"), repository.readWindow(CHAT, from, now, 10, author = "@olena").map { it.text })
        assertEquals(listOf("hers"), repository.readWindow(CHAT, from, now, 10, author = "petrenko").map { it.text })
        assertEquals(listOf("his"), repository.readWindow(CHAT, from, now, 10, author = "serhii").map { it.text })
        assertTrue(repository.readWindow(CHAT, from, now, 10, author = "nobody").isEmpty())
    }

    @Test
    fun `a value longer than its column is truncated instead of rejected`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry("1", "hi", now, username = "u".repeat(200), name = "n".repeat(500)))

        val stored = repository.readWindow(CHAT, now.minusSeconds(60), now, limit = 10).single()

        assertTrue(stored.senderUsername!!.length <= 64)
        assertTrue(stored.senderName!!.length <= 200)
    }

    @Test
    fun `recent drops the message that triggered the turn`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry("1", "earlier", now.minusSeconds(120)))
        repository.record(entry("2", "the question", now))

        val entries = repository.recent(CHAT, limit = 10, since = now.minusSeconds(600), excludeMessageId = "2")

        assertEquals(listOf("earlier"), entries.map { it.text })
    }

    // the bot's own rows carry no message id at all, and `!=` is null rather than true for those in
    // SQL: without the null arm, dropping the triggering message dropped every reply the bot had made.
    @Test
    fun `recent keeps the bot's own messages while dropping the triggering one`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry("1", "earlier", now.minusSeconds(120)))
        repository.record(botEntry("that one is a keeper", now.minusSeconds(60), answering = "1"))
        repository.record(entry("2", "the question", now))

        val entries = repository.recent(CHAT, limit = 10, since = now.minusSeconds(600), excludeMessageId = "2")

        assertEquals(listOf("earlier", "that one is a keeper"), entries.map { it.text })
        // the anchor is what tells the renderer this exchange is already in that user's own history
        assertEquals(listOf(null, "1"), entries.map { it.replyToMessageId })
    }

    @Test
    fun `clear removes the transcript and the cached digests`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry("1", "gone", now))
        repository.storeDigest(CHAT, LocalDate.of(2026, 8, 3), content = "a recap")

        repository.clear(CHAT)

        assertEquals(0L, repository.countInWindow(CHAT, now.minusSeconds(600), now))
        assertNull(repository.digestFor(CHAT, LocalDate.of(2026, 8, 3)))
    }

    @Test
    fun `retention drops rows past the cutoff once pruning kicks in`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig(retentionDays = 1))
        val stale = Instant.now().minus(10, ChronoUnit.DAYS)

        repeat(60) { repository.record(entry(messageId = "${it + 1}", text = "old$it", at = stale)) }

        val fresh = Instant.now()
        repeat(5) { repository.record(entry(messageId = "${1_000 + it}", text = "new$it", at = fresh)) }

        // retention is a pass of its own: nothing a chat writes prunes it any more, which is what left
        // a chat that went quiet holding everything it had.
        assertEquals(1, repository.pruneExpired(maxChats = 10))

        assertEquals(0L, repository.countInWindow(CHAT, stale.minusSeconds(60), stale.plusSeconds(60)))
        assertEquals(5L, repository.countInWindow(CHAT, fresh.minusSeconds(60), fresh.plusSeconds(60)))
    }

    @Test
    fun `retention leaves a chat whose rows are all inside the window alone`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig(retentionDays = 1))
        val fresh = Instant.now()

        repeat(3) { repository.record(entry(messageId = "${it + 1}", text = "recent$it", at = fresh)) }

        assertEquals(0, repository.pruneExpired(maxChats = 10))
        assertEquals(3L, repository.countInWindow(CHAT, fresh.minusSeconds(60), fresh.plusSeconds(60)))
    }

    @Test
    fun `the per-chat row cap trims the oldest rows`() = runBlocking {
        // nothing here is old enough to expire: a busy chat is held to the cap on its own.
        val repository = GroupLogRepository(GroupLogConfig(maxMessagesPerChat = 100, retentionDays = 365))
        val base = Instant.now().minusSeconds(1_000)

        repeat(500) { repository.record(entry(messageId = "${it + 1}", text = "m$it", at = base.plusSeconds(it.toLong()))) }

        assertEquals(1, repository.pruneExpired(maxChats = 10))

        val remaining = repository.countInWindow(CHAT, base.minusSeconds(60), Instant.now())

        assertTrue(remaining <= 100L, "expected the cap to hold, got $remaining rows")

        val oldest = repository.readWindow(CHAT, base.minusSeconds(60), Instant.now(), limit = 1).single()

        assertTrue(oldest.text!!.removePrefix("m").toInt() >= 400)
    }

    @Test
    fun `a stored digest round-trips and is replaced on rewrite`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())
        val day = LocalDate.of(2026, 8, 3)

        repository.storeDigest(CHAT, day, content = "first take")
        assertEquals("first take", repository.digestFor(CHAT, day))

        repository.storeDigest(CHAT, day, content = "second take")
        assertEquals("second take", repository.digestFor(CHAT, day))
    }

    @Test
    fun `an edit rewrites what the transcript quotes`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        repository.record(entry(messageId = "1", text = "wehter in kyiv", at = now.minusSeconds(100)))

        assertTrue(repository.recordEdit(entry(messageId = "1", text = "weather in kyiv", at = now.minusSeconds(100))))

        val entries = repository.readWindow(CHAT, now.minusSeconds(600), now, limit = 10)

        assertEquals(listOf("weather in kyiv"), entries.map { it.text })
    }

    @Test
    fun `an edit drops the cached digest of the day it belongs to`() = runBlocking {
        // the day may already be closed, and nothing else would ever invalidate a stored digest
        val repository = GroupLogRepository(GroupLogConfig())
        val sentAt = now.minusSeconds(100)
        val day = LocalDate.ofInstant(sentAt, ZoneId.systemDefault())

        repository.record(entry(messageId = "1", text = "before", at = sentAt))
        repository.storeDigest(CHAT, day, content = "a recap quoting before")

        repository.recordEdit(entry(messageId = "1", text = "after", at = sentAt))

        assertNull(repository.digestFor(CHAT, day))
    }

    // the reference a messenger issues is opaque text, and a chat log has to hold whatever shape that
    // is: it is part of the row's uniqueness and it is what an edit is looked up by.
    @Test
    fun `a message reference that is not a number survives storage and lookup`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())
        val reference = "1774000000.001500"

        repository.record(entry(messageId = reference, text = "wehter in kyiv", at = now.minusSeconds(100)))

        assertTrue(repository.recordEdit(entry(messageId = reference, text = "weather in kyiv", at = now.minusSeconds(100))))

        val stored = repository.readWindow(CHAT, now.minusSeconds(600), now, limit = 10).single()

        assertEquals(reference, stored.messageId)
        assertEquals("weather in kyiv", stored.text)
    }

    @Test
    fun `an edit of a message the log never saw is not backfilled`() = runBlocking {
        val repository = GroupLogRepository(GroupLogConfig())

        assertFalse(repository.recordEdit(entry(messageId = "7", text = "never recorded", at = now)))
        assertEquals(0L, repository.countInWindow(CHAT, now.minusSeconds(600), now))
    }

    @Test
    fun `an edit leaves the original send time alone`() = runBlocking {
        // sent_at drives the day a message is filed under; an edit must not move it to the edit's own day
        val repository = GroupLogRepository(GroupLogConfig())
        val sentAt = now.minusSeconds(500)

        repository.record(entry(messageId = "1", text = "before", at = sentAt))
        repository.recordEdit(entry(messageId = "1", text = "after", at = now))

        assertEquals(1L, repository.countInWindow(CHAT, sentAt.minusSeconds(1), sentAt.plusSeconds(1)))
    }

    private fun entry(
        messageId: String,
        text: String,
        at: Instant,
        username: String? = "olena",
        name: String? = "Olena Petrenko"
    ) =
        GroupLogEntry(
            chat = CHAT,
            messageId = messageId,
            kind = "text",
            sentAt = at,
            senderId = "1",
            senderUsername = username,
            senderName = name,
            text = text
        )

    private fun botEntry(text: String, at: Instant, answering: String? = null) =
        GroupLogEntry(
            chat = CHAT,
            // delivery does not carry the id of what it sent back, so the bot's own rows have none
            messageId = null,
            kind = GroupLogEntry.BOT_KIND,
            sentAt = at,
            text = text,
            replyToMessageId = answering
        )

    private companion object {
        val CHAT = testChat(-100)
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            accessPolicy = AccessPolicy(),
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
            sitesToken = null,
            sitesUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
