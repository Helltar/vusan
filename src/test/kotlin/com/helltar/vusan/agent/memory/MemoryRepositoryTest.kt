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

        repo.add(MemoryScope.USER, ownerId = 100L, content = "alice detail")
        repo.add(MemoryScope.USER, ownerId = 200L, content = "bob detail")
        // same numeric owner as the user above, but a different scope — must not bleed across.
        repo.add(MemoryScope.CHAT, ownerId = 100L, content = "group detail")

        assertEquals(listOf("alice detail"), repo.load(MemoryScope.USER, 100L).map { it.content })
        assertEquals(listOf("bob detail"), repo.load(MemoryScope.USER, 200L).map { it.content })
        assertEquals(listOf("group detail"), repo.load(MemoryScope.CHAT, 100L).map { it.content })
        assertTrue(repo.load(MemoryScope.CHAT, 999L).isEmpty())
    }

    @Test
    fun `load returns entries oldest-first`() = runBlocking {
        val repo = MemoryRepository()

        repo.add(MemoryScope.USER, 1L, "first")
        repo.add(MemoryScope.USER, 1L, "second")
        repo.add(MemoryScope.USER, 1L, "third")

        assertEquals(listOf("first", "second", "third"), repo.load(MemoryScope.USER, 1L).map { it.content })
    }

    @Test
    fun `adding beyond the cap evicts the oldest`() = runBlocking {
        val repo = MemoryRepository(maxEntriesPerScope = 3)

        repeat(5) { repo.add(MemoryScope.USER, 1L, "item-$it") }

        assertEquals(listOf("item-2", "item-3", "item-4"), repo.load(MemoryScope.USER, 1L).map { it.content })
    }

    @Test
    fun `forget removes the caller's own user memory`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(MemoryScope.USER, ownerId = 100L, content = "alice detail")

        assertTrue(repo.forget(id, userId = 100L, chatId = -1L))
        assertTrue(repo.load(MemoryScope.USER, 100L).isEmpty())
    }

    @Test
    fun `forget refuses another user's memory`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(MemoryScope.USER, ownerId = 100L, content = "alice detail")

        // a different user (200) cannot delete user 100's memory, even with its id.
        assertFalse(repo.forget(id, userId = 200L, chatId = -1L))
        assertEquals(listOf("alice detail"), repo.load(MemoryScope.USER, 100L).map { it.content })
    }

    @Test
    fun `forget removes group memory for any member of that chat`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(MemoryScope.CHAT, ownerId = -500L, content = "group detail")

        // member's own userId differs, but the chatId matches the entry's owner chat.
        assertTrue(repo.forget(id, userId = 100L, chatId = -500L))
        assertTrue(repo.load(MemoryScope.CHAT, -500L).isEmpty())
    }

    @Test
    fun `forget refuses group memory from a different chat`() = runBlocking {
        val repo = MemoryRepository()
        val id = repo.add(MemoryScope.CHAT, ownerId = -500L, content = "group detail")

        assertFalse(repo.forget(id, userId = 100L, chatId = -999L))
        assertEquals(listOf("group detail"), repo.load(MemoryScope.CHAT, -500L).map { it.content })
    }

    @Test
    fun `clearScope wipes one scope and leaves the rest intact`() = runBlocking {
        val repo = MemoryRepository()
        repo.add(MemoryScope.USER, 100L, "alice 1")
        repo.add(MemoryScope.USER, 100L, "alice 2")
        repo.add(MemoryScope.USER, 200L, "bob")
        repo.add(MemoryScope.CHAT, 100L, "group")

        val removed = repo.clearScope(MemoryScope.USER, 100L)

        assertEquals(2, removed)
        assertTrue(repo.load(MemoryScope.USER, 100L).isEmpty())
        assertEquals(listOf("bob"), repo.load(MemoryScope.USER, 200L).map { it.content })
        assertEquals(listOf("group"), repo.load(MemoryScope.CHAT, 100L).map { it.content })
    }

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
            maxMemoryPerScope = 10,
            maxTasksPerUser = 5,
            metricsPort = null,
            openAiStt = null,
            sandboxTimeoutSeconds = 30L,
            sandboxUrl = null,
            systemPrompt = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
