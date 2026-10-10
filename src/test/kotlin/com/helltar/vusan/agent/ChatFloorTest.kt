package com.helltar.vusan.agent

import com.helltar.vusan.agent.ChatFloor.Verdict
import com.helltar.vusan.request.ChatContext
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.SenderContext
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.request.testChat
import com.helltar.vusan.request.testUser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class ChatFloorTest {

    private var now = Instant.parse("2026-01-01T02:00:00Z")
    private val floor = ChatFloor(exempt = { it == testUser(OWNER) }, clock = { now })

    private val talker = requestContext(chatId = GROUP, userId = TALKER, isPrivate = false)
    private val other = requestContext(chatId = GROUP, userId = OTHER, isPrivate = false)

    @Test
    fun `the last answer of a stretch is marked and the next message is turned away`() {
        val verdicts = List(ChatFloor.ANSWERS_PER_STRETCH + 2) { floor.admit(talker) }

        assertEquals(List(ChatFloor.ANSWERS_PER_STRETCH - 1) { Verdict.OPEN }, verdicts.take(ChatFloor.ANSWERS_PER_STRETCH - 1))
        assertEquals(Verdict.LAST_ANSWER, verdicts[ChatFloor.ANSWERS_PER_STRETCH - 1])
        assertEquals(listOf(Verdict.CLOSED, Verdict.CLOSED), verdicts.takeLast(2))
        assertTrue(floor.isClosed(talker))
    }

    @Test
    fun `a line from somebody else hands the floor back`() {
        exhaust(talker)

        floor.lineFrom(testChat(GROUP), threadId = null, speaker = testUser(OTHER))

        assertFalse(floor.isClosed(talker))
        assertEquals(Verdict.OPEN, floor.admit(talker))
    }

    @Test
    fun `the holder's own lines hand nothing back`() {
        exhaust(talker)

        floor.lineFrom(testChat(GROUP), threadId = null, speaker = testUser(TALKER))

        assertEquals(Verdict.CLOSED, floor.admit(talker))
    }

    @Test
    fun `the floor reopens once the bot has been quiet for the cooling period`() {
        exhaust(talker)

        // unanswered messages do not count as the bot speaking, so they do not push the hour back
        now += (ChatFloor.COOLING_PERIOD - 1.minutes).toJavaDuration()
        assertEquals(Verdict.CLOSED, floor.admit(talker))

        now += 1.minutes.toJavaDuration()
        assertFalse(floor.isClosed(talker))
        assertEquals(Verdict.OPEN, floor.admit(talker))
    }

    @Test
    fun `a pause inside a stretch starts it over`() {
        repeat(ChatFloor.ANSWERS_PER_STRETCH - 1) { floor.admit(talker) }

        now += ChatFloor.COOLING_PERIOD.toJavaDuration()

        assertEquals(Verdict.OPEN, floor.admit(talker))
    }

    @Test
    fun `another person's turn starts a stretch of their own`() {
        exhaust(talker)

        assertEquals(Verdict.OPEN, floor.admit(other))
        assertFalse(floor.isClosed(talker))
    }

    @Test
    fun `a private chat is never limited`() {
        val private = requestContext(chatId = TALKER, userId = TALKER, isPrivate = true)

        repeat(ChatFloor.ANSWERS_PER_STRETCH * 2) { assertEquals(Verdict.OPEN, floor.admit(private)) }
        assertFalse(floor.isClosed(private))
    }

    @Test
    fun `the owner is never limited`() {
        val owner = requestContext(chatId = GROUP, userId = OWNER, isPrivate = false)

        repeat(ChatFloor.ANSWERS_PER_STRETCH * 2) { assertEquals(Verdict.OPEN, floor.admit(owner)) }
        assertFalse(floor.isClosed(owner))
    }

    @Test
    fun `topics of one group have floors of their own`() {
        val inTopic = inTopic("7")
        exhaust(inTopic)

        assertTrue(floor.isClosed(inTopic))
        assertFalse(floor.isClosed(talker))
        assertEquals(Verdict.OPEN, floor.admit(talker))

        // and a line in the other topic reopens nothing here
        floor.lineFrom(testChat(GROUP), threadId = null, speaker = testUser(OTHER))
        assertTrue(floor.isClosed(inTopic))
    }

    @Test
    fun `a last answer that never reached the person passes to the next message`() {
        repeat(ChatFloor.ANSWERS_PER_STRETCH - 1) { floor.admit(talker) }
        assertEquals(Verdict.LAST_ANSWER, floor.admit(talker))

        floor.unanswered(talker)

        assertFalse(floor.isClosed(talker))
        assertEquals(Verdict.LAST_ANSWER, floor.admit(talker))
        assertEquals(Verdict.CLOSED, floor.admit(talker))
    }

    @Test
    fun `an unanswered turn gives back only its own sender's count`() {
        repeat(ChatFloor.ANSWERS_PER_STRETCH - 1) { floor.admit(talker) }

        floor.unanswered(other)

        assertEquals(Verdict.LAST_ANSWER, floor.admit(talker))
    }

    @Test
    fun `asking whether the floor is closed counts nothing`() {
        repeat(ChatFloor.ANSWERS_PER_STRETCH - 1) { floor.admit(talker) }

        repeat(5) { assertFalse(floor.isClosed(talker)) }

        assertEquals(Verdict.LAST_ANSWER, floor.admit(talker))
    }

    private fun ChatFloor.isClosed(context: RequestContext): Boolean =
        isClosed(context.chatRef, context.chat.threadId, context.user)

    private fun exhaust(context: RequestContext) {
        repeat(ChatFloor.ANSWERS_PER_STRETCH) { floor.admit(context) }
    }

    private fun inTopic(threadId: String): RequestContext =
        RequestContext(
            platform = Platform.TELEGRAM,
            chat = ChatContext(id = GROUP.toString(), isPrivate = false, threadId = threadId),
            sender = SenderContext(id = TALKER.toString()),
        )

    private companion object {
        const val GROUP = -100L
        const val TALKER = 1L
        const val OTHER = 2L
        const val OWNER = 9L
    }
}
