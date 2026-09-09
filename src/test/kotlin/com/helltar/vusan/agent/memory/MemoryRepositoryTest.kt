package com.helltar.vusan.agent.memory

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.testChat
import com.helltar.vusan.request.testUser
import com.helltar.vusan.request.AccessPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MemoryRepositoryTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-memory-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `memory is isolated by scope and owner`() = runBlocking {
        val repo = MemoryRepository()

        repo.add(testUser(100).memoryOwner, content = "alice detail")
        repo.add(testUser(200).memoryOwner, content = "bob detail")
        // same numeric owner as the user above, but a different scope — must not bleed across.
        repo.add(testChat(100).memoryOwner, content = "group detail")

        assertEquals(listOf("alice detail"), repo.load(testUser(100).memoryOwner).map { it.content })
        assertEquals(listOf("bob detail"), repo.load(testUser(200).memoryOwner).map { it.content })
        assertEquals(listOf("group detail"), repo.load(testChat(100).memoryOwner).map { it.content })
        assertTrue(repo.load(testChat(999).memoryOwner).isEmpty())
    }

    @Test
    fun `load returns entries oldest-first`() = runBlocking {
        val repo = MemoryRepository()

        repo.add(testUser(1).memoryOwner, "first")
        repo.add(testUser(1).memoryOwner, "second")
        repo.add(testUser(1).memoryOwner, "third")

        assertEquals(listOf("first", "second", "third"), repo.load(testUser(1).memoryOwner).map { it.content })
    }

    @Test
    fun `adding beyond the cap evicts the oldest`() = runBlocking {
        val repo = MemoryRepository(maxEntriesPerScope = 3)

        repeat(5) { repo.add(testUser(1).memoryOwner, "item-$it") }

        assertEquals(listOf("item-2", "item-3", "item-4"), repo.load(testUser(1).memoryOwner).map { it.content })
    }

    @Test
    fun `forget removes the caller's own user memory`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(testUser(100).memoryOwner, content = "alice detail")

        assertTrue(repo.forget(id, user = testUser(100), chat = testChat(-1)))
        assertTrue(repo.load(testUser(100).memoryOwner).isEmpty())
    }

    @Test
    fun `forget refuses another user's memory`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(testUser(100).memoryOwner, content = "alice detail")

        // a different user (200) cannot delete user 100's memory, even with its id.
        assertFalse(repo.forget(id, user = testUser(200), chat = testChat(-1)))
        assertEquals(listOf("alice detail"), repo.load(testUser(100).memoryOwner).map { it.content })
    }

    @Test
    fun `forget removes group memory for any member of that chat`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(testChat(-500).memoryOwner, content = "group detail")

        // member's own userId differs, but the chatId matches the entry's owner chat.
        assertTrue(repo.forget(id, user = testUser(100), chat = testChat(-500)))
        assertTrue(repo.load(testChat(-500).memoryOwner).isEmpty())
    }

    @Test
    fun `forget refuses group memory from a different chat`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(testChat(-500).memoryOwner, content = "group detail")

        assertFalse(repo.forget(id, user = testUser(100), chat = testChat(-999)))
        assertEquals(listOf("group detail"), repo.load(testChat(-500).memoryOwner).map { it.content })
    }

    @Test
    fun `clearScope wipes one scope and leaves the rest intact`() = runBlocking {
        val repo = MemoryRepository()
        repo.add(testUser(100).memoryOwner, "alice 1")
        repo.add(testUser(100).memoryOwner, "alice 2")
        repo.add(testUser(200).memoryOwner, "bob")
        repo.add(testChat(100).memoryOwner, "group")

        val removed = repo.clearScope(testUser(100).memoryOwner)

        assertEquals(2, removed)
        assertTrue(repo.load(testUser(100).memoryOwner).isEmpty())
        assertEquals(listOf("bob"), repo.load(testUser(200).memoryOwner).map { it.content })
        assertEquals(listOf("group"), repo.load(testChat(100).memoryOwner).map { it.content })
    }

    // both platforms issue plain numbers, so an unqualified owner would hand one person's memory to
    // whoever matched the id on the other one.
    @Test
    fun `the same id on two platforms is two owners`() = runBlocking {
        val repo = MemoryRepository()
        val onTelegram = testUser(100, Platform.TELEGRAM).memoryOwner
        val onDiscord = testUser(100, Platform.DISCORD).memoryOwner

        repo.add(onTelegram, "lives in Kyiv")
        repo.add(onDiscord, "plays bass")

        assertEquals(listOf("lives in Kyiv"), repo.load(onTelegram).map { it.content })
        assertEquals(listOf("plays bass"), repo.load(onDiscord).map { it.content })

        repo.clearScope(onTelegram)

        assertTrue(repo.load(onTelegram).isEmpty())
        assertEquals(listOf("plays bass"), repo.load(onDiscord).map { it.content })
    }

    @Test
    fun `a memory cannot be forgotten from the other platform`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(testUser(100, Platform.TELEGRAM).memoryOwner, "lives in Kyiv")

        assertFalse(repo.forget(id, testUser(100, Platform.DISCORD), testChat(-500, Platform.DISCORD)))
        assertTrue(repo.forget(id, testUser(100, Platform.TELEGRAM), testChat(-500, Platform.TELEGRAM)))
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
