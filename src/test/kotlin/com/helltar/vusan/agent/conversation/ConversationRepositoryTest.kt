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
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.testScope
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
        history.appendInteraction(DM, exchange("first", "one"))
        history.appendInteraction(DM, exchange("second", "two"))

        val snapshot = history.load(DM)

        assertEquals(2, snapshot.interactions.size)
        assertEquals(listOf("first", "one"), snapshot.interactions[0].turns.map { it.content })
        assertEquals(listOf("second", "two"), snapshot.interactions[1].turns.map { it.content })
        assertEquals(2, snapshot.stats.storedInteractions)
        assertEquals(4, snapshot.stats.storedMessages)
    }

    @Test
    fun `the same user keeps one history per chat`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(DM, exchange("something private", "kept between us"))
        history.appendInteraction(GROUP, exchange("hi everyone", "hello"))

        val inGroup = history.load(GROUP)

        assertEquals(1, inGroup.interactions.size)
        assertEquals(listOf("hi everyone", "hello"), inGroup.interactions.single().turns.map { it.content })
        assertFalse(
            inGroup.interactions.any { interaction -> interaction.turns.any { "private" in it.content } },
            "a private exchange must never be replayable as this user's own words in a group"
        )
        assertEquals(1, history.load(DM).stats.storedInteractions)
    }

    @Test
    fun `a recap checkpoint cannot point at a message from another chat`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(DM, exchange("first", "one"))
        val inDm = history.load(DM).interactions.single()

        assertFalse(
            history.storeSummary(
                scope = GROUP,
                expectedThroughMessageId = 0L,
                throughMessageId = inDm.lastMessageId,
                content = "a recap of messages this chat never had"
            )
        )

        assertEquals(null, history.load(GROUP).summary)
    }

    @Test
    fun `summary checkpoint hides compacted interactions without deleting their raw rows`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(DM, exchange("first", "one"))
        history.appendInteraction(DM, exchange("second", "two"))

        val before = history.load(DM)
        val first = before.interactions.first()

        assertTrue(
            history.storeSummary(
                scope = DM,
                expectedThroughMessageId = 0L,
                throughMessageId = first.lastMessageId,
                content = "The user said first; the assistant replied one."
            )
        )

        val after = history.load(DM)

        assertEquals("The user said first; the assistant replied one.", after.summary)
        assertEquals(listOf("second", "two"), after.interactions.single().turns.map { it.content })
        assertEquals(2, after.stats.storedInteractions)
        assertEquals(1, after.stats.unsummarizedInteractions)
    }

    @Test
    fun `raw retention removes only complete interactions covered by the summary`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(DM, exchange("first", "one"))
        history.appendInteraction(DM, exchange("second", "two"))

        val before = history.load(DM)
        val first = before.interactions.first()
        history.storeSummary(DM, 0L, first.lastMessageId, "first exchange recap")

        val pruned =
            history.pruneCompacted(
                scope = DM,
                maxStoredInteractions = 1,
                rawRetentionCutoff = Instant.EPOCH
            )

        val after = history.load(DM)
        assertEquals(1, pruned)
        assertEquals(1, after.stats.storedInteractions)
        assertEquals(listOf("second", "two"), after.interactions.single().turns.map { it.content })
    }

    @Test
    fun `retention prunes one conversation without touching the same user elsewhere`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(DM, exchange("first", "one"))
        history.appendInteraction(DM, exchange("second", "two"))
        history.appendInteraction(GROUP, exchange("in the group", "answered"))

        val first = history.load(DM).interactions.first()
        history.storeSummary(DM, 0L, first.lastMessageId, "first exchange recap")

        history.pruneCompacted(DM, maxStoredInteractions = 1, rawRetentionCutoff = Instant.EPOCH)

        assertEquals(1, history.load(GROUP).stats.storedInteractions)
    }

    @Test
    fun `clear advances the revision of one conversation and leaves the others alone`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(DM, exchange("first", "one"))
        history.appendInteraction(GROUP, exchange("in the group", "answered"))
        history.appendInteraction(OTHER_USER_IN_GROUP, exchange("other", "answer"))
        val first = history.load(DM).interactions.single()
        history.storeSummary(DM, 0L, first.lastMessageId, "recap")

        assertEquals(0L, history.revision(DM))

        history.clear(DM)

        val cleared = history.load(DM)
        assertEquals(1L, history.revision(DM))
        assertTrue(cleared.interactions.isEmpty())
        assertEquals(null, cleared.summary)

        // the same person in another chat, and another person in the same chat, both untouched.
        assertEquals(
            listOf("in the group", "answered"),
            history.load(GROUP).interactions.single().turns.map { it.content }
        )
        assertEquals(0L, history.revision(GROUP))
        assertEquals(
            listOf("other", "answer"),
            history.load(OTHER_USER_IN_GROUP).interactions.single().turns.map { it.content }
        )

        history.clear(DM)

        assertEquals(2L, history.revision(DM))
    }

    // the recap and the count of wipes share one row now, so storing one must not disturb the other.
    @Test
    fun `a recap stored after a clear keeps the revision`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(DM, exchange("first", "one"))
        history.clear(DM)
        history.appendInteraction(DM, exchange("second", "two"))
        val interaction = history.load(DM).interactions.single()

        assertTrue(history.storeSummary(DM, 0L, interaction.lastMessageId, "recap"))

        assertEquals(1L, history.revision(DM))
        assertEquals("recap", history.load(DM).summary)
    }

    @Test
    fun `the last exchange is the one in this chat, not the users latest anywhere`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(GROUP, exchange("in the group", "answered"))
        val groupExchangeAt = history.lastInteractionAt(GROUP)

        history.appendInteraction(DM, exchange("later, in private", "sure"))

        assertNotNull(groupExchangeAt)
        assertEquals(groupExchangeAt, history.lastInteractionAt(GROUP))
        assertEquals(null, history.lastInteractionAt(OTHER_USER_IN_GROUP))
    }

    // telegram and discord both issue plain numbers, so the same pair of ids names two different people
    // in two different chats. sharing a history between them would replay one person's words as another's.
    @Test
    fun `the same ids on two platforms are two conversations`() = runBlocking {
        val history = ConversationRepository()
        history.appendInteraction(DM, exchange("my bank pin is 1234", "noted"))
        history.appendInteraction(DM_ON_DISCORD, exchange("hello", "hi"))

        assertEquals(
            listOf("my bank pin is 1234", "noted"),
            history.load(DM).interactions.single().turns.map { it.content }
        )

        assertEquals(
            listOf("hello", "hi"),
            history.load(DM_ON_DISCORD).interactions.single().turns.map { it.content }
        )

        history.clear(DM)

        assertEquals(1L, history.revision(DM))
        assertEquals(0L, history.revision(DM_ON_DISCORD))
        assertEquals(1, history.load(DM_ON_DISCORD).interactions.size)
    }

    private fun exchange(user: String, assistant: String): List<ChatTurn> =
        listOf(
            ChatTurn(ChatRole.USER, user),
            ChatTurn(ChatRole.ASSISTANT, assistant)
        )

    private companion object {
        val DM_ON_DISCORD = testScope(userId = 42, chatId = 42, platform = Platform.DISCORD)
        val OTHER_USER_IN_GROUP = testScope(userId = 99, chatId = -100)

        // in telegram a private chat carries the user's own id, so the DM conversation is (42, 42).
        val DM = testScope(userId = 42, chatId = 42)
        val GROUP = testScope(userId = 42, chatId = -100)
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            accessPolicy = AccessPolicy(),
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
            workspaceMaxTimeoutSeconds = 600L,
            workspaceToken = null,
            workspaceUrl = null,
            sitesToken = null,
            sitesUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
