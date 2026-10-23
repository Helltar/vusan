package com.helltar.vusan.tools.diary

import com.helltar.vusan.agent.presence.CHAT
import com.helltar.vusan.agent.presence.DiaryEntry
import com.helltar.vusan.agent.presence.DiaryLookBack
import com.helltar.vusan.agent.presence.DiaryPeriod
import com.helltar.vusan.agent.presence.DiaryRepository
import com.helltar.vusan.agent.presence.DiarySpan
import com.helltar.vusan.agent.presence.testConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.tools.toolFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiaryToolsTest {

    private lateinit var tempDir: Path

    private val repository = DiaryRepository()
    private val now: Instant = Instant.parse("2026-10-23T12:00:00Z")
    private val group = requestContext(chatId = -100, userId = 8, isPrivate = false)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-diary-tools-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a month names every day in it, with the look-backs that reach into it`() = runBlocking {
        store("2026-08-31", "the bridge closed")
        store("2026-09-02", "the ferry was bought")
        store("2026-09-30", "the ferry sailed")
        store("2026-10-01", "the ferry sank")
        lookBack(DiaryPeriod.WEEK, "2026-08-31", "a week of boats")
        lookBack(DiaryPeriod.MONTH, "2026-09-01", "september, the month of the ferry")
        lookBack(DiaryPeriod.MONTH, "2026-08-01", "august, the bridge")

        val result = tools().readDiary(from = "2026-09")

        assertTrue("from 2026-09-01 to 2026-09-30: 2 look-back(s) and 2 daily entries." in result, result)
        assertTrue(
            "<diary>\nweek 2026-08-31..2026-09-06: a week of boats\n\n" +
                    "month 2026-09: september, the month of the ferry\n\n" +
                    "2026-09-02: the ferry was bought\n\n2026-09-30: the ferry sailed\n</diary>" in result,
            result,
        )
        assertFalse("the bridge closed" in result, result)
        assertFalse("the ferry sank" in result, result)
    }

    @Test
    fun `a text finds the entries that mention it, in any case, across the whole diary`() = runBlocking {
        store("2026-09-02", "Bob bought the ferry")
        store("2026-09-03", "carol painted the bridge")
        store("2026-10-20", "bob sold the ferry")

        val result = tools().readDiary(contains = "BOB")

        assertTrue("from 2026-09-02 to 2026-10-23, mentioning `BOB`: 0 look-back(s) and 2 daily entries." in result, result)
        assertTrue("2026-09-02: Bob bought the ferry" in result, result)
        assertTrue("2026-10-20: bob sold the ferry" in result, result)
        assertFalse("carol" in result, result)
    }

    @Test
    fun `a read too wide for its budget keeps the look-backs and the newest days`() = runBlocking {
        (1..20).forEach { store("2026-09-%02d".format(it), "day $it " + "x".repeat(300)) }
        lookBack(DiaryPeriod.MONTH, "2026-09-01", "september")

        val result = tools(budgetChars = 2_000).readDiary()

        assertTrue("Not all of it fits: shown are 1 look-back(s) and the newest 6 daily entries." in result, result)
        assertTrue("month 2026-09: september" in result, result)
        assertTrue("2026-09-20: day 20" in result, result)
        assertFalse("2026-09-14: day 14" in result, result)
    }

    @Test
    fun `nothing in the range says where the diary begins`() = runBlocking {
        store("2026-10-16", "the ferry won")

        assertEquals(
            "Nothing in your diary about this group from 2026-09-01 to 2026-09-30. It begins on 2026-10-16.",
            tools().readDiary(from = "2026-09"),
        )
    }

    @Test
    fun `an empty diary and a private chat say so`() = runBlocking {
        assertTrue(tools().readDiary().startsWith("Your diary about this group is empty"))

        val private = DiaryTools(repository, requestContext(chatId = 8, userId = 8, isPrivate = true), 10_000)

        assertTrue(private.readDiary().startsWith("No diary in a private chat"))
    }

    @Test
    fun `a malformed date or a backward range is refused`() = runBlocking {
        assertTrue("`from` must be a day" in toolFailure { tools().readDiary(from = "last week") })
        assertTrue("must not be after" in toolFailure { tools().readDiary(from = "2026-10-05", to = "2026-10-01") })
    }

    private fun tools(budgetChars: Int = 10_000) = DiaryTools(repository, group, budgetChars, ZoneOffset.UTC) { now }

    private suspend fun store(day: String, content: String) = repository.store(CHAT, DiaryEntry(LocalDate.parse(day), content))

    private suspend fun lookBack(period: DiaryPeriod, firstDay: String, content: String) =
        repository.storeLookBack(CHAT, DiaryLookBack(DiarySpan(period, LocalDate.parse(firstDay)), content))
}
