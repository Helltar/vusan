package com.helltar.vusan.tools.leavealone

import com.helltar.vusan.agent.presence.LeaveAloneRepository
import com.helltar.vusan.agent.presence.testConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.request.testChat
import com.helltar.vusan.request.testUser
import com.helltar.vusan.tools.toolFailure
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaveAloneToolsTest {

    private lateinit var tempDir: Path

    private val repository = LeaveAloneRepository()
    private val now: Instant = Instant.parse("2026-10-12T12:00:00Z")
    private val tools = LeaveAloneTools(repository, requestContext(chatId = -100, userId = 8, isPrivate = false), ZoneOffset.UTC) { now }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-leave-alone-tools-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `without a length the request holds until the day ends`() = runBlocking {
        val result = tools.leaveAlone()

        assertTrue(result.startsWith("Noted"), result)
        assertTrue("until 2026-10-13 00:00" in result, result)
        assertEquals(Instant.parse("2026-10-13T00:00:00Z"), repository.activeUntil(CHAT, BOB, now))
    }

    // said ten minutes before midnight, the end of the day would let it lapse almost at once
    @Test
    fun `without a length the request holds for half a day at the least`() = runBlocking {
        val lateEvening = Instant.parse("2026-10-12T23:50:00Z")
        val tools = LeaveAloneTools(repository, requestContext(chatId = -100, userId = 8, isPrivate = false), ZoneOffset.UTC) { lateEvening }

        tools.leaveAlone()

        assertEquals(Instant.parse("2026-10-13T11:50:00Z"), repository.activeUntil(CHAT, BOB, lateEvening))
    }

    @Test
    fun `a length in hours is kept, up to a week`() = runBlocking {
        tools.leaveAlone(hours = 3)
        assertEquals(now.plusSeconds(3 * 3600), repository.activeUntil(CHAT, BOB, now))

        tools.leaveAlone(hours = 500)
        assertEquals(now.plusSeconds(7 * 24 * 3600), repository.activeUntil(CHAT, BOB, now))
    }

    @Test
    fun `zero lifts the request, and says when there was none`() = runBlocking {
        tools.leaveAlone()

        assertTrue(tools.leaveAlone(hours = 0).startsWith("Lifted"))
        assertNull(repository.activeUntil(CHAT, BOB, now))
        assertTrue(tools.leaveAlone(hours = 0).startsWith("There was no request"))
    }

    @Test
    fun `a negative length is refused`() = runBlocking {
        val message = toolFailure { tools.leaveAlone(hours = -1) }

        assertTrue("0 or more" in message, message)
        assertNull(repository.activeUntil(CHAT, BOB, now))
    }

    private companion object {
        val CHAT = testChat(-100)
        val BOB = testUser(8)
    }
}
