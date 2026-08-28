package com.helltar.vusan.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppConfigTest {

    @Test
    fun `an unset value stays unset so the caller can pick its default`() {
        assertNull(parseIntEnv("MAX_TASKS_PER_USER", null))
        assertNull(parseLongEnv("SANDBOX_TIMEOUT_SECONDS", null))
        assertNull(parseBooleanEnv("GROUP_LOG_ENABLED", null))
        assertEquals(emptySet(), parseIdSetEnv("ALLOWED_IDS", null))
    }

    @Test
    fun `numbers are read, with surrounding whitespace tolerated`() {
        assertEquals(9, parseIntEnv("MAX_TASKS_PER_USER", "9"))
        assertEquals(9, parseIntEnv("MAX_TASKS_PER_USER", " 9 "))
        assertEquals(300L, parseLongEnv("SANDBOX_TIMEOUT_SECONDS", "300"))
        assertEquals(-1, parseIntEnv("MAX_TASKS_PER_USER", "-1"))
    }

    @Test
    fun `a mistyped number stops the startup instead of restoring the default`() {
        // the digit-oh typo is the whole point: it used to read as "unset" and bring back the default
        val failure = assertFailsWith<IllegalStateException> { parseIntEnv("AGENT_MAX_ITERATIONS", "7O") }

        assertContains(failure.message.orEmpty(), "AGENT_MAX_ITERATIONS")
        assertContains(failure.message.orEmpty(), "7O")

        assertFailsWith<IllegalStateException> { parseIntEnv("MAX_TASKS_PER_USER", "many") }
        assertFailsWith<IllegalStateException> { parseLongEnv("SANDBOX_TIMEOUT_SECONDS", "120s") }
        assertFailsWith<IllegalStateException> { parseLongEnv("SANDBOX_TIMEOUT_SECONDS", "1.5") }
    }

    @Test
    fun `booleans are read whatever their case`() {
        assertEquals(true, parseBooleanEnv("GROUP_LOG_ENABLED", "true"))
        assertEquals(true, parseBooleanEnv("GROUP_LOG_ENABLED", "True"))
        assertEquals(true, parseBooleanEnv("GROUP_LOG_ENABLED", "TRUE"))
        assertEquals(false, parseBooleanEnv("GROUP_LOG_ENABLED", "false"))
        assertEquals(false, parseBooleanEnv("GROUP_LOG_ENABLED", "False"))
        assertEquals(false, parseBooleanEnv("GROUP_LOG_ENABLED", " FALSE "))
    }

    @Test
    fun `a boolean spelt some other way never reads as the default`() {
        // silently defaulting here left the group transcript recording after it was asked to stop
        listOf("0", "1", "no", "yes", "off", "on", "disabled").forEach { raw ->
            val failure = assertFailsWith<IllegalStateException> { parseBooleanEnv("GROUP_LOG_ENABLED", raw) }

            assertContains(failure.message.orEmpty(), "GROUP_LOG_ENABLED")
            assertContains(failure.message.orEmpty(), raw)
        }
    }

    @Test
    fun `an id list accepts every separator it documents`() {
        assertEquals(setOf(1L, 2L, 3L, 4L), parseIdSetEnv("ALLOWED_IDS", "1, 2;3\n4"))
        assertEquals(setOf(-100_500L, 7L), parseIdSetEnv("ALLOWED_IDS", "-100500  7"))
        assertEquals(setOf(5L), parseIdSetEnv("ALLOWED_IDS", "5,,  ,5"))
    }

    @Test
    fun `an unreadable id is an error rather than one silently dropped entry`() {
        // dropping one fails open on BANNED_IDS: that person would simply stay unbanned
        val failure = assertFailsWith<IllegalStateException> { parseIdSetEnv("BANNED_IDS", "12345, 6789O") }

        assertContains(failure.message.orEmpty(), "BANNED_IDS")
        assertContains(failure.message.orEmpty(), "6789O")
    }

    @Test
    fun `a number that parses but cannot work is rejected too`() {
        assertFailsWith<IllegalArgumentException> { config(agentMaxIterations = 0) }
        assertFailsWith<IllegalArgumentException> { config(sandboxTimeoutSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { config(maxTasksPerUser = -1) }
        assertFailsWith<IllegalArgumentException> { config(taskMaxLatenessMinutes = -1) }
    }

    @Test
    fun `zero stays a way to turn a per-user limit off`() {
        assertTrue(config(maxTasksPerUser = 0, maxFollowUpsPerUser = 0, maxMemoryPerScope = 0).maxTasksPerUser == 0)
    }

    private fun config(
        agentMaxIterations: Int = 70,
        maxFollowUpsPerUser: Int = 3,
        maxMemoryPerScope: Int = 10,
        maxTasksPerUser: Int = 5,
        sandboxTimeoutSeconds: Long = 120,
        taskMaxLatenessMinutes: Long = 60
    ): AppConfig =
        AppConfig(
            agentMaxIterations = agentMaxIterations,
            allowedIds = setOf(1L),
            appearance = null,
            databasePath = "data/db/vusan.db",
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            llmProvider =
                LlmProviderConfig.Hosted(
                    provider = HostedLlmProvider.OPENAI,
                    apiKey = "key",
                    model = "gpt-5.4-mini",
                    requestTimeout = 120.seconds
                ),
            maxFollowUpsPerUser = maxFollowUpsPerUser,
            maxMemoryPerScope = maxMemoryPerScope,
            maxTasksPerUser = maxTasksPerUser,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            personality = null,
            sandboxTimeoutSeconds = sandboxTimeoutSeconds,
            sandboxUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            taskMaxLatenessMinutes = taskMaxLatenessMinutes,
            tavilyApiKey = null,
            telegramBotToken = "token",
            ytDlpCookiesFile = null
        )
}
