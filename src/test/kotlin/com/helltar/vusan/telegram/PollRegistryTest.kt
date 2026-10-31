package com.helltar.vusan.telegram

import com.helltar.vusan.config.testConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.outbox.BotOutput
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class PollRegistryTest {

    private lateinit var tempDir: Path
    private lateinit var registry: PollRegistry

    private val quiz =
        BotOutput.Quiz(
            question = "Capital of Ukraine?",
            options = listOf("Lviv", "Kyiv", "Odesa"),
            correctOptionIndex = 1,
        )

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-polls-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        registry = PollRegistry(30.days)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a quiz is remembered with its options and the answer that counts`() = runBlocking {
        registry.remember("p1", chatId = -100L, output = quiz)

        val found = assertNotNull(registry.find("p1"))

        assertEquals(-100L, found.chatId)
        assertEquals(listOf("Lviv", "Kyiv", "Odesa"), found.options)
        assertEquals(1, found.correctOptionIndex)
    }

    @Test
    fun `an ordinary poll is remembered with no right answer`() = runBlocking {
        registry.remember("p2", chatId = -100L, output = BotOutput.Poll("Tea or coffee?", listOf("Tea", "Coffee")))

        val found = assertNotNull(registry.find("p2"))

        assertEquals(listOf("Tea", "Coffee"), found.options)
        assertNull(found.correctOptionIndex)
    }

    @Test
    fun `an answer to a poll nobody remembers finds nothing`() = runBlocking {
        assertNull(registry.find("never-sent"))
    }

    // telegram may deliver a poll's creation twice; the second write is the same poll and must neither fail
    // nor replace it.
    @Test
    fun `remembering the same poll twice keeps one row`() = runBlocking {
        registry.remember("p1", chatId = -100L, output = quiz)
        registry.remember("p1", chatId = -100L, output = quiz)

        assertEquals(listOf("Lviv", "Kyiv", "Odesa"), assertNotNull(registry.find("p1")).options)
    }

    @Test
    fun `a poll old enough to be forgotten is dropped by the maintenance pass`() = runBlocking<Unit> {
        registry.remember("p1", chatId = -100L, output = quiz)
        // retention no longer waits for the next poll: a chat asking one question and stopping kept it
        // forever.
        delay(10)
        val between = Instant.now()
        delay(10)
        registry.remember("p2", chatId = -100L, output = quiz)

        assertEquals(1, registry.pruneExpired(between.plusMillis(30.days.inWholeMilliseconds)))

        assertNull(registry.find("p1"))
        assertNotNull(registry.find("p2"))
    }

    // an output that is neither leaves no row: no poll id could ever match it.
    @Test
    fun `a plain message is not remembered as a poll`() = runBlocking {
        registry.remember("p1", chatId = -100L, output = BotOutput.Text("hello"))

        assertNull(registry.find("p1"))
    }
}
