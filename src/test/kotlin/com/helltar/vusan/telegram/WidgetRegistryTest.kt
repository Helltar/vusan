package com.helltar.vusan.telegram

import com.helltar.vusan.config.testConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.WidgetRequests
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.SentWidget
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.runBlocking

class WidgetRegistryTest {

    private lateinit var tempDir: Path
    private lateinit var registry: WidgetRegistry

    private val group = ChatTarget(chatId = -100L, messageThreadId = 3)

    private val poll =
        BotOutput.Widget(
            html = "<p>poll</p>",
            caption = "Lunch poll",
            requests = WidgetRequests.TALLY,
            publicData = tallyPublicData(0, emptyList()),
        )

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-widgets-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        registry = WidgetRegistry(30.days)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a sent widget is remembered with its topic, purpose and state`() = runBlocking {
        registry.remember(group, SentWidget(7, widgetId = null), poll)

        val found = assertNotNull(registry.find(-100L, 7))

        assertEquals(3, found.threadId)
        assertEquals(WidgetRequests.TALLY, found.requests)
        assertEquals("Lunch poll", found.caption)
        assertEquals(poll.publicData, found.publicData)
    }

    @Test
    fun `a press counts, a new choice replaces it and the same choice takes it back`() = runBlocking {
        registry.remember(group, SentWidget(7, widgetId = null), poll)
        val widget = assertNotNull(registry.find(-100L, 7))

        registry.tally(widget, userId = 1, choice = "pizza")
        val second = registry.tally(widget, userId = 2, choice = "pizza")
        assertEquals("""{"rev":2,"counts":{"pizza":2},"total":2}""", second.publicData)

        val switched = registry.tally(widget, userId = 2, choice = "sushi")
        assertEquals("sushi", switched.mine)
        assertEquals(3, switched.record.revision)

        val withdrawn = registry.tally(widget, userId = 2, choice = "sushi")
        assertNull(withdrawn.mine)
        assertEquals("""{"rev":4,"counts":{"pizza":1},"total":1}""", withdrawn.publicData)
        assertEquals(withdrawn.publicData, registry.find(-100L, 7)?.publicData)
    }

    @Test
    fun `a read press changes nothing and tells the person their own choice`() = runBlocking {
        registry.remember(group, SentWidget(7, widgetId = null), poll)
        val widget = assertNotNull(registry.find(-100L, 7))
        registry.tally(widget, userId = 1, choice = "pizza")

        val read = registry.tally(widget, userId = 1, choice = WidgetRegistry.TALLY_READ)

        assertFalse(read.changed)
        assertEquals("pizza", read.mine)
        assertEquals(1, registry.find(-100L, 7)?.revision)
    }

    @Test
    fun `a choice that would overflow the shared state is refused`() = runBlocking {
        registry.remember(group, SentWidget(7, widgetId = null), poll)
        val widget = assertNotNull(registry.find(-100L, 7))

        // each long choice adds about 70 bytes of counts
        val outcomes = (1..20).map { registry.tally(widget, userId = it.toLong(), choice = "option-$it-" + "x".repeat(50)) }

        assertTrue(outcomes.any { it.refused })
        val published = assertNotNull(assertNotNull(registry.find(-100L, 7)).publicData)
        assertTrue(published.toByteArray().size <= 1024)
    }

    @Test
    fun `a shared widget posted again counts into one tally, kept on the first message`() = runBlocking {
        registry.remember(group, SentWidget(7, widgetId = "88"), BotOutput.Widget("<p>poll</p>", "Lunch poll", WidgetRequests.TALLY, shared = true))
        registry.remember(ChatTarget(-200L), SentWidget(9, widgetId = "88"), BotOutput.SharedWidget("88", "Lunch poll again"))

        val repost = assertNotNull(registry.find(-200L, 9))
        assertEquals(WidgetRequests.TALLY, repost.requests)
        assertEquals("Lunch poll again", repost.caption)

        registry.tally(repost, userId = 1, choice = "pizza")
        val fromCopy = registry.tally(assertNotNull(registry.origin("88")), userId = 2, choice = "pizza")

        assertEquals(7, fromCopy.record.messageId)
        assertEquals("""{"rev":2,"counts":{"pizza":2},"total":2}""", fromCopy.publicData)
        assertEquals(fromCopy.publicData, registry.find(-200L, 9)?.publicData)
    }

    @Test
    fun `a state the bot writes is kept, and an empty one clears it`() = runBlocking {
        registry.remember(group, SentWidget(7, widgetId = null), BotOutput.Widget("<p>board</p>", "Chess", WidgetRequests.AGENT, chatData = "{}"))
        val widget = assertNotNull(registry.find(-100L, 7))

        registry.recordState(widget, chatData = """{"turn":"b"}""", publicData = null)
        assertEquals("""{"turn":"b"}""", registry.find(-100L, 7)?.chatData)

        registry.recordState(assertNotNull(registry.find(-100L, 7)), chatData = "", publicData = null)
        assertNull(registry.find(-100L, 7)?.chatData)
    }

    @Test
    fun `the chat's widgets are listed newest first, a topic's only its own`() = runBlocking {
        registry.remember(group, SentWidget(7, widgetId = null), poll)
        registry.remember(ChatTarget(-100L, messageThreadId = 5), SentWidget(8, widgetId = null), poll)
        registry.remember(group, SentWidget(9, widgetId = null), poll)

        assertEquals(listOf(9, 7), registry.recent(-100L, threadId = 3, limit = 10).map { it.messageId })
        assertEquals(3, registry.recent(-100L, threadId = null, limit = 10).size)
    }

    @Test
    fun `an expired widget goes with its votes`() = runBlocking {
        registry.remember(group, SentWidget(7, widgetId = null), poll)
        registry.tally(assertNotNull(registry.find(-100L, 7)), userId = 1, choice = "pizza")

        assertEquals(0, registry.pruneExpired(Instant.now()))
        assertEquals(1, registry.pruneExpired(Instant.now().plusSeconds(31L * 24 * 3600)))
        assertNull(registry.find(-100L, 7))
    }

    @Test
    fun `the answer to a press adds the presser's own choice to the counts`() {
        assertEquals(
            """{"rev":2,"counts":{"in":2},"total":2,"mine":"in"}""",
            tallyAnswer("""{"rev":2,"counts":{"in":2},"total":2}""", mine = "in", refused = false),
        )
        assertEquals(
            """{"rev":0,"counts":{},"total":0,"mine":null,"refused":true}""",
            tallyAnswer(tallyPublicData(0, emptyList()), mine = null, refused = true),
        )
    }
}
