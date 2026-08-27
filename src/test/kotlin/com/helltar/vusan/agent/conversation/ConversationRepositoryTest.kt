package com.helltar.vusan.agent.conversation

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class ConversationRepositoryTest {

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
        val history = ConversationRepository()
        history.appendInteraction(USER, DM, exchange("first", "one"))
        history.appendInteraction(USER, DM, exchange("second", "two"))

        val snapshot = history.load(USER, DM)

        assertEquals(2, snapshot.interactions.size)
        assertEquals(listOf("first", "one"), snapshot.interactions[0].turns.map { it.content })
        assertEquals(listOf("second", "two"), snapshot.interactions[1].turns.map { it.content })
        assertEquals(2, snapshot.stats.storedInteractions)
        assertEquals(4, snapshot.stats.storedMessages)
    }

    @Test
    fun `the same user keeps one history per chat`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(USER, DM, exchange("something private", "kept between us"))
        history.appendInteraction(USER, GROUP, exchange("hi everyone", "hello"))

        val inGroup = history.load(USER, GROUP)

        assertEquals(1, inGroup.interactions.size)
        assertEquals(listOf("hi everyone", "hello"), inGroup.interactions.single().turns.map { it.content })
        assertFalse(
            inGroup.interactions.any { interaction -> interaction.turns.any { "private" in it.content } },
            "a private exchange must never be replayable as this user's own words in a group"
        )
        assertEquals(1, history.load(USER, DM).stats.storedInteractions)
    }

    @Test
    fun `a recap checkpoint cannot point at a message from another chat`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(USER, DM, exchange("first", "one"))
        val inDm = history.load(USER, DM).interactions.single()

        assertFalse(
            history.storeSummary(
                userId = USER,
                chatId = GROUP,
                expectedThroughMessageId = 0L,
                throughMessageId = inDm.lastMessageId,
                content = "a recap of messages this chat never had"
            )
        )

        assertEquals(null, history.load(USER, GROUP).summary)
    }

    @Test
    fun `summary checkpoint hides compacted interactions without deleting their raw rows`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(USER, DM, exchange("first", "one"))
        history.appendInteraction(USER, DM, exchange("second", "two"))

        val before = history.load(USER, DM)
        val first = before.interactions.first()

        assertTrue(
            history.storeSummary(
                userId = USER,
                chatId = DM,
                expectedThroughMessageId = 0L,
                throughMessageId = first.lastMessageId,
                content = "The user said first; the assistant replied one."
            )
        )

        val after = history.load(USER, DM)

        assertEquals("The user said first; the assistant replied one.", after.summary)
        assertEquals(listOf("second", "two"), after.interactions.single().turns.map { it.content })
        assertEquals(2, after.stats.storedInteractions)
        assertEquals(1, after.stats.unsummarizedInteractions)
    }

    @Test
    fun `raw retention removes only complete interactions covered by the summary`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(USER, DM, exchange("first", "one"))
        history.appendInteraction(USER, DM, exchange("second", "two"))

        val before = history.load(USER, DM)
        val first = before.interactions.first()
        history.storeSummary(USER, DM, 0L, first.lastMessageId, "first exchange recap")

        val pruned =
            history.pruneCompacted(
                userId = USER,
                chatId = DM,
                maxStoredInteractions = 1,
                rawRetentionCutoff = Instant.EPOCH
            )

        val after = history.load(USER, DM)
        assertEquals(1, pruned)
        assertEquals(1, after.stats.storedInteractions)
        assertEquals(listOf("second", "two"), after.interactions.single().turns.map { it.content })
    }

    @Test
    fun `retention prunes one conversation without touching the same user elsewhere`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(USER, DM, exchange("first", "one"))
        history.appendInteraction(USER, DM, exchange("second", "two"))
        history.appendInteraction(USER, GROUP, exchange("in the group", "answered"))

        val first = history.load(USER, DM).interactions.first()
        history.storeSummary(USER, DM, 0L, first.lastMessageId, "first exchange recap")

        history.pruneCompacted(USER, DM, maxStoredInteractions = 1, rawRetentionCutoff = Instant.EPOCH)

        assertEquals(1, history.load(USER, GROUP).stats.storedInteractions)
    }

    @Test
    fun `clear advances the revision of one conversation and leaves the others alone`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(USER, DM, exchange("first", "one"))
        history.appendInteraction(USER, GROUP, exchange("in the group", "answered"))
        history.appendInteraction(OTHER_USER, GROUP, exchange("other", "answer"))
        val first = history.load(USER, DM).interactions.single()
        history.storeSummary(USER, DM, 0L, first.lastMessageId, "recap")

        assertEquals(0L, history.revision(USER, DM))

        history.clear(USER, DM)

        val cleared = history.load(USER, DM)
        assertEquals(1L, history.revision(USER, DM))
        assertTrue(cleared.interactions.isEmpty())
        assertEquals(null, cleared.summary)

        // the same person in another chat, and another person in the same chat, both untouched.
        assertEquals(
            listOf("in the group", "answered"),
            history.load(USER, GROUP).interactions.single().turns.map { it.content }
        )
        assertEquals(0L, history.revision(USER, GROUP))
        assertEquals(
            listOf("other", "answer"),
            history.load(OTHER_USER, GROUP).interactions.single().turns.map { it.content }
        )

        history.clear(USER, DM)

        assertEquals(2L, history.revision(USER, DM))
    }

    @Test
    fun `the last exchange is the one in this chat, not the users latest anywhere`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(USER, GROUP, exchange("in the group", "answered"))
        val groupExchangeAt = history.lastInteractionAt(USER, GROUP)

        history.appendInteraction(USER, DM, exchange("later, in private", "sure"))

        assertNotNull(groupExchangeAt)
        assertEquals(groupExchangeAt, history.lastInteractionAt(USER, GROUP))
        assertEquals(null, history.lastInteractionAt(OTHER_USER, GROUP))
    }

    private fun exchange(user: String, assistant: String): List<ChatTurn> =
        listOf(
            ChatTurn(ChatRole.USER, user),
            ChatTurn(ChatRole.ASSISTANT, assistant)
        )

    private companion object {
        const val USER = 42L
        const val OTHER_USER = 99L

        // in telegram a private chat carries the user's own id, so the DM conversation is (42, 42).
        const val DM = 42L
        const val GROUP = -100L
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            allowedIds = emptySet(),
            appearance = null,
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
            maxFollowUpsPerUser = 3,
            maxMemoryPerScope = 10,
            maxTasksPerUser = 5,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            sandboxTimeoutSeconds = 30L,
            sandboxUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
