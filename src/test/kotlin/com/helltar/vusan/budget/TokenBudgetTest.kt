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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

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

        budget.record(inputTokens = 1_000_000, outputTokens = 1_000_000)

        assertNull(budget.exhaustedFor())
    }

    @Test
    fun `spending past the limit blocks until the next midnight in the budget zone`() = runBlocking {
        val budget = TokenBudget(TokenBudgetConfig(dailyTokens = 1_000), clockAt(NOON_UTC))

        budget.record(inputTokens = 600, outputTokens = 300)
        assertNull(budget.exhaustedFor(), "900 of 1000 tokens still leaves room")

        budget.record(inputTokens = 100, outputTokens = 0)

        assertEquals(12.hours, budget.exhaustedFor(), "12:00 UTC to the next 00:00 UTC")
    }

    @Test
    fun `the reset follows the configured zone`() = runBlocking {
        val kyiv = TokenBudgetConfig(dailyTokens = 10, zone = ZoneId.of("Europe/Kyiv"))
        val budget = TokenBudget(kyiv, clockAt(NOON_UTC))

        budget.record(inputTokens = 10, outputTokens = 0)

        // 12:00 UTC is 15:00 in Kyiv in July, so the budget comes back in nine hours, not twelve.
        assertEquals(9.hours, budget.exhaustedFor())
    }

    @Test
    fun `the day's spend survives a restart`() = runBlocking {
        val config = TokenBudgetConfig(dailyTokens = 1_000)

        TokenBudget(config, clockAt(NOON_UTC)).record(inputTokens = 999, outputTokens = 0)

        val afterRestart = TokenBudget(config, clockAt(NOON_UTC))
        assertNull(afterRestart.exhaustedFor())

        afterRestart.record(inputTokens = 1, outputTokens = 0)
        assertEquals(12.hours, afterRestart.exhaustedFor(), "the restarted process resumed the same day's spend")
    }

    @Test
    fun `a new day starts from zero`() = runBlocking {
        val config = TokenBudgetConfig(dailyTokens = 1_000)
        val clock = MovableClock(NOON_UTC)
        val budget = TokenBudget(config, clock)

        budget.record(inputTokens = 1_000, outputTokens = 0)
        assertNotNull(budget.exhaustedFor())

        clock.now = NOON_UTC.plus(1, ChronoUnit.DAYS)

        assertNull(budget.exhaustedFor())
    }

    @Test
    fun `unknown token counts are not charged`() = runBlocking {
        val budget = TokenBudget(TokenBudgetConfig(dailyTokens = 10), clockAt(NOON_UTC))

        budget.record(inputTokens = null, outputTokens = null)

        assertNull(budget.exhaustedFor())
    }

    private class MovableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
    }

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneId.of("UTC"))

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
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )

    private companion object {
        val NOON_UTC: Instant = Instant.parse("2026-07-15T12:00:00Z")
    }
}
