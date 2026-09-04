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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class GroupLogReaderTest {

    private lateinit var tempDir: Path
    private lateinit var repository: GroupLogRepository

    private val zone = ZoneId.of("Europe/Kyiv")

    // 2026-08-04 15:00 in Kyiv, so "today" and the days before it are unambiguous.
    private val now: Instant = Instant.parse("2026-08-04T12:00:00Z")

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-chat-log-reader-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        repository = GroupLogRepository(GroupLogConfig())
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `an empty window says so instead of returning a blank transcript`() = runBlocking {
        val result = reader().read(CHAT, 1.hours, now = now)

        assertContains(result, "No messages recorded")
    }

    @Test
    fun `a window that fits is quoted whole and never reaches the digester`() = runBlocking {
        val digester = RecordingDigester()
        record(1L, "first", now.minusSeconds(600))
        record(2L, "second", now.minusSeconds(300))

        val result = reader(digester).read(CHAT, 1.hours, now = now)

        assertContains(result, "Messages in this window: 2 (exact, for the whole window).")
        assertContains(result, "<transcript>")
        assertContains(result, "first")
        assertContains(result, "second")
        assertTrue(digester.days.isEmpty(), "a fitting window must not cost an LLM call")
    }

    @Test
    fun `an overflowing window without a digester truncates and says how much it dropped`() = runBlocking {
        repeat(200) { record(it + 1L, "message number $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, digester = null, budgetChars = 500, zone = zone)
            .read(CHAT, 1.hours, now = now)

        assertContains(result, "Messages in this window: 200 (exact, for the whole window).")
        assertContains(result, "Showing the newest")
        assertContains(result, "did not fit")
        assertContains(result, "never by counting what is quoted")
    }

    @Test
    fun `a truncated window reports the whole count and not the quoted one`() = runBlocking {
        repeat(200) { record(it + 1L, "message number $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, digester = null, budgetChars = 500, zone = zone)
            .read(CHAT, 1.hours, now = now)

        val quoted = Regex("""Showing the newest (\d+)""").find(result)?.groupValues?.get(1)?.toInt()

        assertNotNull(quoted, result)
        assertTrue(quoted < 200, "the budget must have dropped part of the window, otherwise this proves nothing")
        assertContains(result, "Messages in this window: 200")
    }

    @Test
    fun `a window inside today truncates instead of widening itself to the whole day`() = runBlocking {
        val digester = RecordingDigester()
        repeat(200) { record(it + 1L, "message number $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, digester, budgetChars = 500, zone = zone)
            .read(CHAT, 1.hours, now = now)

        assertContains(result, "Window: 2026-08-04T14:00 .. 2026-08-04T15:00 Europe/Kyiv.")
        assertContains(result, "Showing the newest")
        assertFalse(result.contains("closed days are summarized"), "there is no closed day in this window")
        assertTrue(digester.days.isEmpty(), "a window with nothing closed in it must not cost an LLM call")
    }

    @Test
    fun `an author query stays raw even when it overflows`() = runBlocking {
        val digester = RecordingDigester()
        repeat(200) { record(it + 1L, "from olena $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, digester, budgetChars = 500, zone = zone)
            .read(CHAT, 1.hours, author = "olena", now = now)

        assertContains(result, "<transcript>")
        assertTrue(digester.days.isEmpty(), "a per-author question cannot be answered from whole-chat digests")
    }

    @Test
    fun `an author query counts that person over the whole window even when it is truncated`() = runBlocking {
        repeat(120) { record(it + 1L, "olena $it", now.minusSeconds(3_000L - it)) }
        repeat(30) { recordFrom(500L + it, "petro $it", now.minusSeconds(2_000L - it), "petro", "Petro Koval") }

        val result = GroupLogReader(repository, RecordingDigester(), budgetChars = 500, zone = zone)
            .read(CHAT, 1.hours, author = "petro", now = now)

        assertContains(result, "Messages in this window: 30 from `petro` (exact, for the whole window).")
    }

    @Test
    fun `an overflowing window digests closed days and quotes today`() = runBlocking {
        val digester = RecordingDigester()

        repeat(80) { record(1_000L + it, "two days ago $it", instantAt(2026, 8, 2, 10)) }
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, digester, budgetChars = 1_200, zone = zone)
            .read(CHAT, 3.days, now = now)

        assertEquals(listOf(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3)), digester.days.sorted())
        assertContains(result, """<day date="2026-08-02">""")
        assertContains(result, """<day date="2026-08-03">""")
        assertContains(result, """<today date="2026-08-04" messages="80"""")
        assertContains(result, "today")
        assertFalse(digester.days.contains(LocalDate.of(2026, 8, 4)), "today must never be digested")
    }

    @Test
    fun `the digested count covers the snapped window rather than the narrower one asked for`() = runBlocking {
        // 15:00 minus 24h lands mid-day on the 3rd, and the digest path then widens the window to that
        // whole day. the count has to follow, or the header contradicts the window it prints.
        repeat(60) { record(1_000L + it, "early on the 3rd $it", instantAt(2026, 8, 3, 9)) }
        repeat(60) { record(2_000L + it, "late on the 3rd $it", instantAt(2026, 8, 3, 20)) }
        repeat(60) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, RecordingDigester(), budgetChars = 1_200, zone = zone)
            .read(CHAT, 24.hours, now = now)

        assertContains(result, "Window: 2026-08-03T00:00 .. 2026-08-04T15:00 Europe/Kyiv.")
        assertContains(result, "Messages in this window: 180 (exact, for the whole window).")
    }

    @Test
    fun `a today block that does not fit says how many of its messages are quoted`() = runBlocking {
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today number $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, RecordingDigester(), budgetChars = 1_200, zone = zone)
            .read(CHAT, 2.days, now = now)

        val quoted =
            Regex("""<today date="2026-08-04" messages="(\d+)" quoted="(\d+)">""").find(result)?.groupValues

        assertNotNull(quoted, result)
        assertEquals("80", quoted[1])
        assertTrue(quoted[2].toInt() < 80, "the today share of a 1200 char budget cannot hold 80 messages")
    }

    @Test
    fun `a closed day is digested once and served from the cache after that`() = runBlocking {
        val digester = RecordingDigester()

        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val reader = GroupLogReader(repository, digester, budgetChars = 1_200, zone = zone)

        reader.read(CHAT, 2.days, now = now)
        assertEquals(1, digester.days.size)

        reader.read(CHAT, 2.days, now = now)
        assertEquals(1, digester.days.size, "the cached recap must not be recomputed")

        assertEquals("recap of 2026-08-03", repository.digestFor(CHAT, LocalDate.of(2026, 8, 3)))
    }

    @Test
    fun `today is never cached`() = runBlocking {
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        GroupLogReader(repository, RecordingDigester(), budgetChars = 1_200, zone = zone)
            .read(CHAT, 2.days, now = now)

        assertEquals(null, repository.digestFor(CHAT, LocalDate.of(2026, 8, 4)))
    }

    @Test
    fun `a digester that fails leaves the day out instead of failing the read`() = runBlocking {
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, FailingDigester(), budgetChars = 1_200, zone = zone)
            .read(CHAT, 2.days, now = now)

        assertContains(result, """<today date="2026-08-04" messages="80"""")
        assertFalse(result.contains("<day "), "a failed digest must not produce an empty day block")
    }

    private fun reader(digester: GroupLogDigester? = null) =
        GroupLogReader(repository, digester, budgetChars = 10_000, zone = zone)

    private suspend fun record(messageId: Long, text: String, at: Instant) =
        recordFrom(messageId, text, at, username = "olena", name = "Olena Petrenko")

    private suspend fun recordFrom(messageId: Long, text: String, at: Instant, username: String, name: String) {
        repository.record(
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
        )
    }

    private fun instantAt(year: Int, month: Int, day: Int, hour: Int): Instant =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant()

    private class RecordingDigester : GroupLogDigester {
        val days = mutableListOf<LocalDate>()

        override suspend fun digest(day: LocalDate, transcript: String): String {
            days += day
            return "recap of $day"
        }
    }

    private class FailingDigester : GroupLogDigester {
        override suspend fun digest(day: LocalDate, transcript: String): String? = error("provider is down")
    }

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
