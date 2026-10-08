package com.helltar.vusan.agent.presence

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.testChat
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

internal val CHAT: ChatRef = testChat(-100)

internal fun person(
    messageId: String,
    text: String,
    at: Instant,
    name: String = "Alice",
    senderId: String = "7",
    chat: ChatRef = CHAT,
) =
    GroupLogEntry(
        chat = chat,
        messageId = messageId,
        kind = "text",
        sentAt = at,
        senderId = senderId,
        senderName = name,
        text = text,
    )

internal fun bot(text: String, at: Instant, chat: ChatRef = CHAT) =
    GroupLogEntry(chat = chat, messageId = null, kind = GroupLogEntry.BOT_KIND, sentAt = at, text = text)

internal fun testConfig(dbPath: String) =
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
        openAiImageApiKey = null,
        openAiImage = null,
        openAiStt = null,
        personality = null,
        regolithToken = null,
        regolithUrl = null,
        searxngUrl = null,
        selfImageFile = null,
        tavilyApiKey = null,
        telegramBotToken = "test",
        ytDlpCookiesFile = null,
    )
