package com.helltar.vusan.config

import com.helltar.vusan.config.llm.LlmProviderConfig
import com.helltar.vusan.request.AccessPolicy
import kotlin.time.Duration.Companion.seconds

/** An [AppConfig] with every optional integration off, for tests that only need a database at [dbPath]. */
internal fun testConfig(dbPath: String, accessPolicy: AccessPolicy = AccessPolicy()) =
    AppConfig(
        agentMaxModelCalls = 70,
        accessPolicy = accessPolicy,
        appearance = null,
        databasePath = dbPath,
        elevenLabsApiKey = null,
        elevenLabsTts = null,
        giphyApiKey = null,
        klipyApiKey = null,
        llmProvider = LlmProviderConfig.OpenAi(
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
