package com.helltar.vusan.budget

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.config.TokenBudgetConfig
import com.helltar.vusan.infra.Db
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private const val ALICE = 1L
private const val BOB = 2L

class TokenBudgetTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-budget-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `no budget never blocks`() = runBlocking {
        val budget = TokenBudget(TokenBudgetConfig(), clockAt(NOON_UTC))

        budget.record(ALICE, inputTokens = 1_000_000, outputTokens = 1_000_000)

        assertNull(budget.stopFor(ALICE))
    }

    @Test
    fun `spending past the limit blocks until the next midnight in the budget zone`() = runBlocking {
        val budget = TokenBudget(limitOf(1_000), clockAt(NOON_UTC))

        budget.record(ALICE, inputTokens = 600, outputTokens = 300)
        assertNull(budget.stopFor(ALICE), "900 of 1000 tokens still leaves room")

        budget.record(ALICE, inputTokens = 100, outputTokens = 0)

        val stop = assertIs<TokenBudgetStop.DayBudget>(budget.stopFor(ALICE))
        assertEquals(12.hours, stop.untilReset, "12:00 UTC to the next 00:00 UTC")
    }

    @Test
    fun `the day's ceiling stops everyone, not only the user who spent it`() = runBlocking {
        val budget = TokenBudget(limitOf(1_000), clockAt(NOON_UTC))

        budget.record(ALICE, inputTokens = 1_000, outputTokens = 0)

        assertIs<TokenBudgetStop.DayBudget>(budget.stopFor(BOB))
    }

    @Test
    fun `the reset follows the configured zone`() = runBlocking {
        val kyiv = TokenBudgetConfig(dailyTokens = 10, zone = ZoneId.of("Europe/Kyiv"))
        val budget = TokenBudget(kyiv, clockAt(NOON_UTC))

        budget.record(ALICE, inputTokens = 10, outputTokens = 0)

        // 12:00 UTC is 15:00 in Kyiv in July, so the budget comes back in nine hours, not twelve.
        assertEquals(9.hours, assertNotNull(budget.stopFor(ALICE)).untilReset)
    }

    @Test
    fun `the day's spend survives a restart`() = runBlocking {
        val config = limitOf(1_000)

        TokenBudget(config, clockAt(NOON_UTC)).record(ALICE, inputTokens = 999, outputTokens = 0)

        val afterRestart = TokenBudget(config, clockAt(NOON_UTC))
        assertNull(afterRestart.stopFor(ALICE))

        afterRestart.record(ALICE, inputTokens = 1, outputTokens = 0)
        assertIs<TokenBudgetStop.DayBudget>(
            afterRestart.stopFor(ALICE),
            "the restarted process resumed the same day's spend"
        )
    }

    @Test
    fun `a new day starts from zero`() = runBlocking {
        val clock = MovableClock(NOON_UTC)
        val budget = TokenBudget(limitOf(1_000), clock)

        budget.record(ALICE, inputTokens = 1_000, outputTokens = 0)
        assertNotNull(budget.stopFor(ALICE))

        clock.now = NOON_UTC.plus(1, ChronoUnit.DAYS)

        assertNull(budget.stopFor(ALICE))
    }

    @Test
    fun `unknown token counts are not charged`() = runBlocking {
        val budget = TokenBudget(limitOf(10), clockAt(NOON_UTC))

        budget.record(ALICE, inputTokens = null, outputTokens = null)

        assertNull(budget.stopFor(ALICE))
    }

    @Test
    fun `nobody is held to a share while the day is young`() = runBlocking {
        val budget = TokenBudget(limitOf(1_000), clockAt(NOON_UTC))

        // 650 of 1000 spent, all of it by one person, and their share of two is 500 — but the day is only
        // two thirds gone, so the tokens are still first come, first served.
        budget.record(ALICE, inputTokens = 600, outputTokens = 0)
        budget.record(BOB, inputTokens = 50, outputTokens = 0)

        assertNull(budget.stopFor(ALICE))
    }

    @Test
    fun `once the day runs low the heavy user waits and the light one carries on`() = runBlocking {
        val budget = TokenBudget(limitOf(1_000), clockAt(NOON_UTC))

        budget.record(ALICE, inputTokens = 600, outputTokens = 0)
        budget.record(BOB, inputTokens = 50, outputTokens = 0)
        budget.record(ALICE, inputTokens = 60, outputTokens = 0)

        // 710 of 1000 is past the 70% mark, and Alice is over her 500 of two shares; Bob is nowhere near his.
        assertIs<TokenBudgetStop.UserShare>(budget.stopFor(ALICE))
        assertNull(budget.stopFor(BOB))
    }

    @Test
    fun `the share is split between the people who actually used the bot`() = runBlocking {
        val clock = MovableClock(NOON_UTC)
        val config = limitOf(1_000)

        // four people used the bot yesterday, so today is shared four ways even before they show up.
        (1L..4L).forEach { TokenBudget(config, clock).record(it, inputTokens = 10, outputTokens = 0) }

        clock.now = NOON_UTC.plus(1, ChronoUnit.DAYS)
        val budget = TokenBudget(config, clock)

        budget.record(ALICE, inputTokens = 700, outputTokens = 0)

        // Alice alone reached the 70% mark, and 700 is well past her 250 of four shares.
        assertIs<TokenBudgetStop.UserShare>(budget.stopFor(ALICE))
        assertNull(budget.stopFor(BOB), "the rest of the day is still there for everyone else")
    }

    @Test
    fun `a share that ran out comes back with the new day`() = runBlocking {
        val clock = MovableClock(NOON_UTC)
        val budget = TokenBudget(limitOf(1_000), clock)

        budget.record(ALICE, inputTokens = 700, outputTokens = 0)
        budget.record(BOB, inputTokens = 10, outputTokens = 0)
        assertIs<TokenBudgetStop.UserShare>(budget.stopFor(ALICE))

        clock.now = NOON_UTC.plus(1, ChronoUnit.DAYS)

        assertNull(budget.stopFor(ALICE))
    }

    @Test
    fun `background work is charged to nobody's share`() = runBlocking {
        val budget = TokenBudget(limitOf(1_000), clockAt(NOON_UTC))

        // the sticker worker and the group digest run outside any turn: they spend the day's budget, but
        // there is no share to hold them to, and they never take one away from a person either.
        budget.record(userId = null, inputTokens = 800, outputTokens = 0)

        assertNull(budget.stopFor(null))
        assertNull(budget.stopFor(ALICE), "a person with a clean sheet is not blocked by background work")

        budget.record(userId = null, inputTokens = 200, outputTokens = 0)

        assertIs<TokenBudgetStop.DayBudget>(budget.stopFor(null))
    }

    private fun limitOf(dailyTokens: Long) = TokenBudgetConfig(dailyTokens = dailyTokens)

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneId.of("UTC"))

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            allowedIds = emptySet(),
            appearance = null,
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
            maxFollowUpsPerUser = 3,
            maxMemoryPerScope = 10,
            maxTasksPerUser = 5,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            personality = null,
            sandboxTimeoutSeconds = 30L,
            sandboxUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )

    private companion object {
        val NOON_UTC: Instant = Instant.parse("2026-07-15T12:00:00Z")
    }
}
