package com.helltar.vusan.agent.presence

import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.UserRef
import com.helltar.vusan.request.testChat
import com.helltar.vusan.request.testUser
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaveAloneRepositoryTest {

    private lateinit var tempDir: Path

    private val repository = LeaveAloneRepository()
    private val now: Instant = Instant.parse("2026-10-12T12:00:00Z")
    private val tonight: Instant = Instant.parse("2026-10-12T21:00:00Z")

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-leave-alone-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a request holds until its end and not past it`() = runBlocking {
        repository.store(CHAT, BOB, tonight)

        assertEquals(tonight, repository.activeUntil(CHAT, BOB, now))
        assertNull(repository.activeUntil(CHAT, BOB, tonight))
    }

    @Test
    fun `a request is one person's in one chat`() = runBlocking {
        repository.store(CHAT, BOB, tonight)

        assertNull(repository.activeUntil(CHAT, testUser(9), now))
        assertNull(repository.activeUntil(testChat(-200), BOB, now))
    }

    @Test
    fun `asking again replaces the end`() = runBlocking {
        repository.store(CHAT, BOB, tonight)
        repository.store(CHAT, BOB, now.plusSeconds(60))

        assertEquals(now.plusSeconds(60), repository.activeUntil(CHAT, BOB, now))
    }

    @Test
    fun `a lifted request is gone, and lifting nothing says so`() = runBlocking {
        repository.store(CHAT, BOB, tonight)

        assertTrue(repository.lift(CHAT, BOB))
        assertNull(repository.activeUntil(CHAT, BOB, now))
        assertFalse(repository.lift(CHAT, BOB))
    }

    @Test
    fun `a chat's requests are read together, the run-out ones left out`() = runBlocking {
        repository.store(CHAT, BOB, tonight)
        repository.store(CHAT, testUser(9), now.minusSeconds(1))
        repository.store(testChat(-200), testUser(10), tonight)

        assertEquals(mapOf(BOB.id to tonight), repository.activeInChat(CHAT, now))
    }

    @Test
    fun `retention drops what has run out and nothing else`() = runBlocking {
        repository.store(CHAT, BOB, tonight)
        repository.store(CHAT, testUser(9), now)

        assertEquals(1, repository.pruneExpired(now))
        assertEquals(mapOf(BOB.id to tonight), repository.activeInChat(CHAT, now))
    }

    @Test
    fun `a request cannot cross platforms`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            repository.store(CHAT, UserRef(Platform.DISCORD, "8"), tonight)
        }
    }

    private companion object {
        val BOB = testUser(8)
    }
}
