package com.helltar.vusan.config

import com.helltar.vusan.request.AccessPolicy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class AppConfigTest {

    @Test
    fun `an unset value stays unset so the caller can pick its default`() {
        assertNull(parseIntEnv("MAX_TASKS_PER_USER", null))
        assertNull(parseLongEnv("TASK_MAX_LATENESS_MINUTES", null))
        assertNull(parseBooleanEnv("GROUP_LOG_ENABLED", null))
        assertEquals(emptySet(), parseIdSetEnv("ALLOWED_IDS", null))
    }

    @Test
    fun `numbers are read, with surrounding whitespace tolerated`() {
        assertEquals(9, parseIntEnv("MAX_TASKS_PER_USER", "9"))
        assertEquals(9, parseIntEnv("MAX_TASKS_PER_USER", " 9 "))
        assertEquals(300L, parseLongEnv("TASK_MAX_LATENESS_MINUTES", "300"))
        assertEquals(-1, parseIntEnv("MAX_TASKS_PER_USER", "-1"))
    }

    @Test
    fun `a mistyped number stops the startup instead of restoring the default`() {
        // the digit-oh typo is the whole point: it used to read as "unset" and bring back the default
        val failure = assertFailsWith<IllegalStateException> { parseIntEnv("AGENT_MAX_MODEL_CALLS", "7O") }

        assertContains(failure.message.orEmpty(), "AGENT_MAX_MODEL_CALLS")
        assertContains(failure.message.orEmpty(), "7O")

        assertFailsWith<IllegalStateException> { parseIntEnv("MAX_TASKS_PER_USER", "many") }
        assertFailsWith<IllegalStateException> { parseLongEnv("TASK_MAX_LATENESS_MINUTES", "120s") }
        assertFailsWith<IllegalStateException> { parseLongEnv("TASK_MAX_LATENESS_MINUTES", "1.5") }
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
    fun `a boolean spelled some other way never reads as the default`() {
        // silently defaulting here left the group transcript recording after it was asked to stop
        listOf("0", "1", "no", "yes", "off", "on", "disabled").forEach { raw ->
            val failure = assertFailsWith<IllegalStateException> { parseBooleanEnv("GROUP_LOG_ENABLED", raw) }

            assertContains(failure.message.orEmpty(), "GROUP_LOG_ENABLED")
            assertContains(failure.message.orEmpty(), raw)
        }
    }

    @Test
    fun `an id list accepts every separator it documents`() {
        assertEquals(
            setOf("telegram:1", "telegram:2", "telegram:3", "telegram:4"),
            parseIdSetEnv("ALLOWED_IDS", "1, 2;3\n4"),
        )

        assertEquals(setOf("telegram:-100500", "telegram:7"), parseIdSetEnv("ALLOWED_IDS", "-100500  7"))
        assertEquals(setOf("telegram:5"), parseIdSetEnv("ALLOWED_IDS", "5,,  ,5"))
    }

    // a bare number is what every existing deployment has written, so it keeps meaning Telegram; a
    // second messenger's ids have to name themselves rather than land in the same set as numbers.
    @Test
    fun `an id names its platform, or is read as a telegram one`() {
        assertEquals(setOf("discord:99"), parseIdSetEnv("ALLOWED_IDS", "discord:99"))
        assertEquals(setOf("telegram:99"), parseIdSetEnv("ALLOWED_IDS", "TELEGRAM:99"))
        assertNotEquals(parseIdSetEnv("ALLOWED_IDS", "discord:99"), parseIdSetEnv("ALLOWED_IDS", "99"))

        val failure = assertFailsWith<IllegalStateException> { parseIdSetEnv("ALLOWED_IDS", "matrix:@ann") }
        assertContains(failure.message.orEmpty(), "matrix:@ann")
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
        assertFailsWith<IllegalArgumentException> { config(agentMaxModelCalls = 2) }
    }

    // a role reads the same settings under its own prefix, so the message has to say which one was wrong
    @Test
    fun `a zero timeout or window names the variable it came from`() {
        val failure = assertFailsWith<IllegalArgumentException> { parsePositiveLongEnv("VISION_REQUEST_TIMEOUT_SECONDS", "0") }

        assertEquals("VISION_REQUEST_TIMEOUT_SECONDS=[0] must be positive", failure.message)
        assertEquals(400_000L, parsePositiveLongEnv("LLM_CONTEXT_WINDOW_TOKENS", "400000"))
        assertEquals(null, parsePositiveLongEnv("LLM_CONTEXT_WINDOW_TOKENS", null))
    }

    // the switch on with the transcript off would leave the feature with nothing to judge a message by
    @Test
    fun `answering without a mention stops the startup without the group log`() {
        val addressing =
            AddressingConfig(
                provider = LlmProviderConfig.OpenAi(apiKey = "key", model = "gpt-6-luna", requestTimeout = 30.seconds, envPrefix = "ADDRESSING"),
                names = emptyList(),
            )

        assertFailsWith<IllegalArgumentException> { config(addressing = addressing, groupLog = GroupLogConfig(enabled = false)) }
        config(addressing = addressing)
    }

    // a service reached without its secret is a misconfiguration, never a service reached anonymously
    @Test
    fun `a sandbox url without a token stops the startup`() {
        assertFailsWith<IllegalArgumentException> { config(regolithUrl = "http://regolith:8080") }
    }

    private fun config(
        agentMaxModelCalls: Int = 70,
        regolithUrl: String? = null,
        addressing: AddressingConfig? = null,
        groupLog: GroupLogConfig = GroupLogConfig(),
    ): AppConfig =
        AppConfig(
            addressing = addressing,
            groupLog = groupLog,
            agentMaxModelCalls = agentMaxModelCalls,
            accessPolicy = AccessPolicy(allowed = setOf("telegram:1")),
            appearance = null,
            databasePath = "data/db/vusan.db",
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            klipyApiKey = null,
            llmProvider =
                LlmProviderConfig.OpenAi(
                    apiKey = "key",
                    model = "gpt-5.4-mini",
                    requestTimeout = 120.seconds,
                ),
            maxConcurrentTurns = 4,
            image = null,
            openAiStt = null,
            personality = null,
            regolithToken = null,
            regolithUrl = regolithUrl,
            searxngUrl = null,
            selfImageFile = null,
            tavilyApiKey = null,
            telegramBotToken = "token",
            ytDlpCookiesFile = null,
        )
}
