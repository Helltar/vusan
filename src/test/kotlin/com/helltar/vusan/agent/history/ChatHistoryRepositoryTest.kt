package com.helltar.vusan.agent.history

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.ChatMessagesTable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert

class ChatHistoryRepositoryTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-chat-history-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `append and load preserve whole interactions`() = runBlocking {
        val history = ChatHistoryRepository()
        history.appendInteraction(42L, exchange("first", "one"))
        history.appendInteraction(42L, exchange("second", "two"))

        val snapshot = history.load(42L)

        assertEquals(2, snapshot.interactions.size)
        assertEquals(listOf("first", "one"), snapshot.interactions[0].turns.map { it.content })
        assertEquals(listOf("second", "two"), snapshot.interactions[1].turns.map { it.content })
        assertEquals(2, snapshot.stats.storedInteractions)
        assertEquals(4, snapshot.stats.storedMessages)
    }

    @Test
    fun `summary checkpoint hides compacted interactions without deleting their raw rows`() = runBlocking {
        val history = ChatHistoryRepository()
        history.appendInteraction(42L, exchange("first", "one"))
        history.appendInteraction(42L, exchange("second", "two"))

        val before = history.load(42L)
        val first = before.interactions.first()

        assertTrue(
            history.storeSummary(
                userId = 42L,
                expectedThroughMessageId = 0L,
                throughMessageId = first.lastMessageId,
                content = "The user said first; the assistant replied one."
            )
        )

        val after = history.load(42L)

        assertEquals("The user said first; the assistant replied one.", after.summary)
        assertEquals(listOf("second", "two"), after.interactions.single().turns.map { it.content })
        assertEquals(2, after.stats.storedInteractions)
        assertEquals(1, after.stats.unsummarizedInteractions)
    }

    @Test
    fun `raw retention removes only complete interactions covered by the summary`() = runBlocking {
        val history = ChatHistoryRepository()
        history.appendInteraction(42L, exchange("first", "one"))
        history.appendInteraction(42L, exchange("second", "two"))

        val before = history.load(42L)
        val first = before.interactions.first()
        history.storeSummary(42L, 0L, first.lastMessageId, "first exchange recap")

        val pruned =
            history.pruneCompacted(
                userId = 42L,
                maxStoredInteractions = 1,
                rawRetentionCutoff = Instant.EPOCH
            )

        val after = history.load(42L)
        assertEquals(1, pruned)
        assertEquals(1, after.stats.storedInteractions)
        assertEquals(listOf("second", "two"), after.interactions.single().turns.map { it.content })
    }

    @Test
    fun `legacy rows without interaction ids are grouped from each user turn`() = runBlocking {
        dbTransaction {
            listOf(
                ChatTurn(ChatRole.USER, "first"),
                ChatTurn(ChatRole.ASSISTANT, "one"),
                ChatTurn(ChatRole.USER, "second"),
                ChatTurn(ChatRole.ASSISTANT, "two")
            ).forEach { turn ->
                ChatMessagesTable.insert {
                    it[ChatMessagesTable.userId] = 42L
                    it[ChatMessagesTable.role] = turn.role
                    it[ChatMessagesTable.content] = turn.content
                }
            }
        }

        val snapshot = ChatHistoryRepository().load(42L)

        assertEquals(2, snapshot.interactions.size)
        assertEquals(listOf("first", "one"), snapshot.interactions[0].turns.map { it.content })
        assertEquals(listOf("second", "two"), snapshot.interactions[1].turns.map { it.content })
    }

    @Test
    fun `clear advances only the users revision and removes transcript and recap`() = runBlocking {
        val history = ChatHistoryRepository()
        history.appendInteraction(42L, exchange("first", "one"))
        history.appendInteraction(99L, exchange("other", "answer"))
        val first = history.load(42L).interactions.single()
        history.storeSummary(42L, 0L, first.lastMessageId, "recap")

        assertEquals(0L, history.revision(42L))

        history.clear(42L)

        val cleared = history.load(42L)
        assertEquals(1L, history.revision(42L))
        assertTrue(cleared.interactions.isEmpty())
        assertEquals(null, cleared.summary)
        assertEquals(listOf("other", "answer"), history.load(99L).interactions.single().turns.map { it.content })
        assertEquals(0L, history.revision(99L))

        history.clear(42L)

        assertEquals(2L, history.revision(42L))
    }

    private fun exchange(user: String, assistant: String): List<ChatTurn> =
        listOf(
            ChatTurn(ChatRole.USER, user),
            ChatTurn(ChatRole.ASSISTANT, assistant)
        )

    private fun testConfig(dbPath: String) =
        AppConfig(
            allowedIds = emptySet(),
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
            maxMemoryPerScope = 10,
            maxTasksPerUser = 5,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            sandboxTimeoutSeconds = 30L,
            sandboxUrl = null,
            searxngUrl = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
