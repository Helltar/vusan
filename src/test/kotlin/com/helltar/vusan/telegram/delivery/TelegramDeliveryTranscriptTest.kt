package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.request.testChat
import java.io.Serializable
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.api.objects.chat.Chat
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient

class TelegramDeliveryTranscriptTest {

    private lateinit var tempDir: Path

    private val acceptingClient: TelegramClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
        ) { _, method, args ->
            check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }
            CompletableFuture.completedFuture(if (args.single() is SendMessage) Message() else true)
        } as TelegramClient

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-delivery-transcript-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    // a turn queued behind this one reads the transcript as soon as this one ends, before its delivery
    // has recorded anything — so the plan has to be there already, under the time it appeared.
    @Test
    fun `a plan announced mid-turn is in the transcript from the moment it appeared, and only once`() = runBlocking {
        val groupLog = GroupLogRepository(GroupLogConfig())
        val delivery = TelegramDelivery(acceptingClient, groupLog = groupLog)

        delivery.recordPostedMidTurn(
            requestContext(chatId = CHAT_ID, userId = 2, messageId = "77", isPrivate = false),
            "I will build the game",
        )

        assertEquals(listOf("I will build the game"), transcript(groupLog).map { it.text })

        val outbox =
            BotOutbox().apply {
                recordDelivered("I will build the game")
                enqueueText("here it is")
            }

        delivery.send(groupMessage(), AgentResult(outputs = outbox.pending, comment = null))

        val lines = transcript(groupLog)

        assertEquals(listOf("I will build the game", "here it is"), lines.map { it.text })
        assertEquals(listOf("77", "77"), lines.map { it.replyToMessageId })
    }

    @Test
    fun `what a turn says mid-run in a private chat stays out of the transcript`() = runBlocking {
        val groupLog = GroupLogRepository(GroupLogConfig())

        TelegramDelivery(acceptingClient, groupLog = groupLog)
            .recordPostedMidTurn(requestContext(chatId = 2, userId = 2, isPrivate = true), "I will build the game")

        assertTrue(groupLog.recent(testChat(2), limit = 10, since = Instant.EPOCH).isEmpty())
    }

    // a send refused after every fallback never reached the chat, so the transcript must not say it did,
    // and the words that were to ride on it as a caption are sent on their own
    @Test
    fun `a send that fails after every fallback leaves no transcript row and sends its caption as text`() = runBlocking {
        val groupLog = GroupLogRepository(GroupLogConfig())
        val client = RefusingClient()
        val outbox = BotOutbox().apply { enqueue(BotOutput.Photo(byteArrayOf(1), "chart.png")) }

        TelegramDelivery(client.proxy, groupLog = groupLog)
            .send(groupMessage(), AgentResult(outputs = outbox.pending, comment = "Here is the chart"))

        assertTrue(transcript(groupLog).isEmpty())
        // the caption's own text fallback inside the photo chain, then the comment sent on its own
        assertEquals(2, client.requests.count { it == "SendMessage" })
    }

    private class RefusingClient {

        val requests = mutableListOf<String>()

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java),
            ) { _, method, args ->
                check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }
                requests += args.single()::class.java.simpleName

                CompletableFuture.failedFuture<Any>(
                    TelegramApiRequestException(
                        "Error executing request",
                        ApiResponse.builder<Serializable>()
                            .ok(false)
                            .errorCode(400)
                            .errorDescription("Bad Request: message is too long")
                            .build(),
                    ),
                )
            } as TelegramClient
    }

    private suspend fun transcript(groupLog: GroupLogRepository) =
        groupLog.recent(testChat(CHAT_ID), limit = 10, since = Instant.EPOCH)

    private fun groupMessage() =
        Message().apply {
            messageId = 77
            chat = Chat.builder().id(CHAT_ID).type("supergroup").build()
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
        const val CHAT_ID = -7L
    }
}
