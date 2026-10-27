package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.config.llm.LlmProviderConfig
import com.helltar.vusan.infra.Db
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.testChat
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
        repository = GroupLogRepository(GroupLogConfig(), zone)
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
    fun `a window that fits is quoted whole and never reaches the diary`() = runBlocking {
        val diary = RecordingDiary()
        record(1L, "first", now.minusSeconds(600))
        record(2L, "second", now.minusSeconds(300))

        val result = reader(diary).read(CHAT, 1.hours, now = now)

        assertContains(result, "Messages in this window: 2 (exact, for the whole window).")
        assertContains(result, "<transcript>")
        assertContains(result, "first")
        assertContains(result, "second")
        assertTrue(diary.reads.isEmpty(), "a fitting window has no use for the diary")
    }

    @Test
    fun `an overflowing window without a diary truncates and says how much it dropped`() = runBlocking {
        repeat(200) { record(it + 1L, "message number $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, diary = null, budgetChars = 500)
            .read(CHAT, 1.hours, now = now)

        assertContains(result, "Messages in this window: 200 (exact, for the whole window).")
        assertContains(result, "Showing the newest")
        assertContains(result, "did not fit")
        assertContains(result, "never by counting what is quoted")
    }

    @Test
    fun `a truncated window reports the whole count and not the quoted one`() = runBlocking {
        repeat(200) { record(it + 1L, "message number $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, diary = null, budgetChars = 500)
            .read(CHAT, 1.hours, now = now)

        val quoted = Regex("""Showing the newest (\d+)""").find(result)?.groupValues?.get(1)?.toInt()

        assertNotNull(quoted, result)
        assertTrue(quoted < 200, "the budget must have dropped part of the window, otherwise this proves nothing")
        assertContains(result, "Messages in this window: 200")
    }

    @Test
    fun `a window inside today truncates instead of widening itself to the whole day`() = runBlocking {
        val diary = RecordingDiary()
        repeat(200) { record(it + 1L, "message number $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, diary, budgetChars = 500)
            .read(CHAT, 1.hours, now = now)

        assertContains(result, "Window: 2026-08-04T14:00 .. 2026-08-04T15:00 Europe/Kyiv.")
        assertContains(result, "Showing the newest")
        assertFalse(result.contains("diary entry"), "there is no closed day in this window")
        assertTrue(diary.reads.isEmpty(), "a window with nothing closed in it has no entry to give")
    }

    @Test
    fun `an author query stays raw even when it overflows`() = runBlocking {
        val diary = RecordingDiary()
        repeat(200) { record(it + 1L, "from olena $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, diary, budgetChars = 500)
            .read(CHAT, 1.hours, author = "olena", now = now)

        assertContains(result, "<transcript>")
        assertTrue(diary.reads.isEmpty(), "a per-author question cannot be answered from entries about the whole chat")
    }

    @Test
    fun `an author query counts that person over the whole window even when it is truncated`() = runBlocking {
        repeat(120) { record(it + 1L, "olena $it", now.minusSeconds(3_000L - it)) }
        repeat(30) { recordFrom(500L + it, "petro $it", now.minusSeconds(2_000L - it), "petro", "Petro Koval") }

        val result = GroupLogReader(repository, RecordingDiary(), budgetChars = 500)
            .read(CHAT, 1.hours, author = "petro", now = now)

        assertContains(result, "Messages in this window: 30 from `petro` (exact, for the whole window).")
    }

    @Test
    fun `a search quotes its matches from every day and never reaches the diary`() = runBlocking {
        val diary = RecordingDiary()

        repeat(80) { record(1_000L + it, "two days ago $it", instantAt(2026, 8, 2, 10)) }
        record(1_500L, "the Budget is due friday", instantAt(2026, 8, 2, 11))
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        record(2_500L, "who approved the budget", instantAt(2026, 8, 3, 11))
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, diary, budgetChars = 1_200)
            .read(CHAT, 3.days, contains = "budget", now = now)

        assertContains(result, "Messages in this window: 2 mentioning `budget` (exact, for the whole window).")
        assertContains(result, "the Budget is due friday")
        assertContains(result, "who approved the budget")
        assertFalse(result.contains("Showing the newest"), "every match fit")
        assertTrue(diary.reads.isEmpty(), "an entry drops exactly the message a search is after")
    }

    @Test
    fun `a search too big to quote shows the newest matches under the exact count`() = runBlocking {
        repeat(200) { record(it + 1L, "budget line $it", now.minusSeconds(3_000L - it)) }

        val result = GroupLogReader(repository, RecordingDiary(), budgetChars = 500)
            .read(CHAT, 1.hours, author = "olena", contains = "budget", now = now)

        assertContains(result, "Messages in this window: 200 from `olena` mentioning `budget` (exact, for the whole window).")
        assertContains(result, "Showing the newest")
        assertContains(result, "budget line 199")
    }

    @Test
    fun `a search with no match says what was looked for`() = runBlocking {
        record(1L, "first", now.minusSeconds(600))

        val result = reader().read(CHAT, 1.hours, contains = "budget", now = now)

        assertContains(result, "No messages mentioning `budget` between")
    }

    @Test
    fun `an overflowing window gives closed days as diary entries and quotes today`() = runBlocking {
        val diary = RecordingDiary(LocalDate.of(2026, 8, 2) to "a quiet sunday", LocalDate.of(2026, 8, 3) to "the budget fight")

        repeat(80) { record(1_000L + it, "two days ago $it", instantAt(2026, 8, 2, 10)) }
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, diary, budgetChars = 1_200)
            .read(CHAT, 3.days, now = now)

        assertEquals(listOf(LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 8, 3)), diary.reads)
        assertContains(result, "<day date=\"2026-08-02\">\na quiet sunday\n</day>")
        assertContains(result, "<day date=\"2026-08-03\">\nthe budget fight\n</day>")
        assertContains(result, "how you remembered the day, not what was said")
        assertContains(result, """<today date="2026-08-04" messages="80"""")
        assertFalse(result.contains("yesterday 1"), "a closed day is given by its entry, not quoted")
    }

    @Test
    fun `a closed day without an entry is named rather than left out silently`() = runBlocking {
        val diary = RecordingDiary(LocalDate.of(2026, 8, 3) to "the budget fight")

        repeat(80) { record(1_000L + it, "two days ago $it", instantAt(2026, 8, 2, 10)) }
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, diary, budgetChars = 1_200)
            .read(CHAT, 2.days, now = now)

        assertContains(result, "No diary entry for 2026-08-02.")
        assertContains(result, "the budget fight")
    }

    @Test
    fun `a diary that cannot be read leaves the days without an entry instead of failing the read`() = runBlocking {
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, { _, _, _ -> error("database is locked") }, budgetChars = 1_200)
            .read(CHAT, 2.days, now = now)

        assertContains(result, """<today date="2026-08-04" messages="80"""")
        assertContains(result, "No diary entry for 2026-08-03.")
        assertFalse(result.contains("<day "), "a failed read must not produce a day block")
    }

    @Test
    fun `entries that do not all fit keep the newest days`() = runBlocking {
        val diary =
            RecordingDiary(
                LocalDate.of(2026, 8, 1) to "first ".repeat(60),
                LocalDate.of(2026, 8, 2) to "second ".repeat(60),
                LocalDate.of(2026, 8, 3) to "third ".repeat(60),
            )

        repeat(80) { record(1_000L + it, "early $it", instantAt(2026, 8, 1, 10)) }
        repeat(80) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, diary, budgetChars = 1_200)
            .read(CHAT, 4.days, now = now)

        assertContains(result, "third")
        assertFalse(result.contains("first first"), "the oldest entry is the one dropped")
        assertContains(result, "entries of the window did not fit and are omitted")
    }

    @Test
    fun `the count covers the snapped window rather than the narrower one asked for`() = runBlocking {
        // 15:00 minus 24h lands mid-day on the 3rd, and the diary path then widens the window to that
        // whole day. the count has to follow, or the header contradicts the window it prints.
        repeat(60) { record(1_000L + it, "early on the 3rd $it", instantAt(2026, 8, 3, 9)) }
        repeat(60) { record(2_000L + it, "late on the 3rd $it", instantAt(2026, 8, 3, 20)) }
        repeat(60) { record(3_000L + it, "today $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, RecordingDiary(), budgetChars = 1_200)
            .read(CHAT, 24.hours, now = now)

        assertContains(result, "Window: 2026-08-03T00:00 .. 2026-08-04T15:00 Europe/Kyiv.")
        assertContains(result, "Messages in this window: 180 (exact, for the whole window).")
    }

    @Test
    fun `a today block that does not fit says how many of its messages are quoted`() = runBlocking {
        repeat(80) { record(2_000L + it, "yesterday $it", instantAt(2026, 8, 3, 10)) }
        repeat(80) { record(3_000L + it, "today number $it", instantAt(2026, 8, 4, 11)) }

        val result = GroupLogReader(repository, RecordingDiary(), budgetChars = 1_200)
            .read(CHAT, 2.days, now = now)

        val quoted =
            Regex("""<today date="2026-08-04" messages="(\d+)" quoted="(\d+)">""").find(result)?.groupValues

        assertNotNull(quoted, result)
        assertEquals("80", quoted[1])
        assertTrue(quoted[2].toInt() < 80, "the today share of a 1200 char budget cannot hold 80 messages")
    }

    private fun reader(diary: RememberedDays? = null) =
        GroupLogReader(repository, diary, budgetChars = 10_000)

    private suspend fun record(messageId: Long, text: String, at: Instant) =
        recordFrom(messageId, text, at, username = "olena", name = "Olena Petrenko")

    private suspend fun recordFrom(messageId: Long, text: String, at: Instant, username: String, name: String) {
        repository.record(
            GroupLogEntry(
                chat = CHAT,
                messageId = messageId.toString(),
                kind = "text",
                sentAt = at,
                senderId = "1",
                senderUsername = username,
                senderName = name,
                text = text,
            ),
        )
    }

    private fun instantAt(year: Int, month: Int, day: Int, hour: Int): Instant =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant()

    private class RecordingDiary(vararg entries: Pair<LocalDate, String>) : RememberedDays {
        private val written = entries.toMap()
        val reads = mutableListOf<Pair<LocalDate, LocalDate>>()

        override suspend fun between(chat: ChatRef, from: LocalDate, to: LocalDate): Map<LocalDate, String> {
            reads += from to to

            return written.filterKeys { it in from..to }
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
            llmProvider =
                LlmProviderConfig.OpenAi(
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

    private companion object {
        val CHAT = testChat(-100)
    }
}
