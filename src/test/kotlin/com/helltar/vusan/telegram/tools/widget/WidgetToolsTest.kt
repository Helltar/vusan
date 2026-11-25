package com.helltar.vusan.telegram.tools.widget

import com.helltar.vusan.config.testConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.WidgetRequests
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.telegram.RecordedCall
import com.helltar.vusan.telegram.WidgetRegistry
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.SentWidget
import com.helltar.vusan.telegram.recordingTelegramClient
import com.helltar.vusan.tools.toolFailure
import com.helltar.vusan.tools.toolFunctions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WidgetToolsTest {

    private lateinit var tempDir: Path
    private lateinit var registry: WidgetRegistry

    private val calls = mutableListOf<RecordedCall>()
    private val client = recordingTelegramClient(calls)
    // the chat's id stands for any chat the widgets were sent to; the group case has its own test
    private val context = requestContext(chatId = -100L, isPrivate = true)

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-widget-tools-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        registry = WidgetRegistry()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    private fun tools(outbox: BotOutbox = BotOutbox()) = WidgetTools(client, registry, context, outbox)

    @Test
    fun `a widget is queued with the reply, and a tally starts with empty counts`() = runBlocking {
        val outbox = BotOutbox()

        val answer = tools(outbox).sendWidget(html = "<p>Lunch?</p>", caption = "Lunch poll", requests = "Tally", shared = true)

        val widget = assertIs<BotOutput.Widget>(outbox.pending.single().output)
        assertEquals(WidgetRequests.TALLY, widget.requests)
        assertTrue(widget.shared)
        assertEquals("""{"rev":0,"counts":{},"total":0}""", widget.publicData)
        assertContains(answer, "bytes compressed")
    }

    @Test
    fun `a widget over telegram's compressed limit is refused with its size`() = runBlocking {
        // random hex packs to about half, so 24 KB of it is far past 4096 bytes and still under 32 KB
        val noise = Random(1).nextBytes(12_000).joinToString("") { "%02x".format(it) }

        val failure = toolFailure { tools().sendWidget(html = "<p>$noise</p>", caption = "Noise") }

        assertContains(failure, "after compression")
    }

    @Test
    fun `in a group a widget goes only to the person's private chat`() = runBlocking {
        val outbox = BotOutbox()
        val group = WidgetTools(client, registry, requestContext(chatId = -200L, isPrivate = false), outbox)

        assertContains(toolFailure { group.sendWidget(html = "<p>x</p>", caption = "X") }, "replyInPrivateMessages")

        outbox.useDirectMessages()
        group.sendWidget(html = "<p>x</p>", caption = "X")

        assertTrue(outbox.pending.single().toPrivate)
    }

    @Test
    fun `an unknown request mode is refused`() = runBlocking {
        val failure = toolFailure { tools().sendWidget(html = "<p>x</p>", caption = "X", requests = "webhook") }

        assertContains(failure, "`none`, `tally` or `agent`")
    }

    @Test
    fun `a chat refusing photos refuses widgets`() = runBlocking {
        val outbox = BotOutbox(ChatCapabilities(photos = false))

        val answer = tools(outbox).sendWidget(html = "<p>x</p>", caption = "X")

        assertContains(answer, "does not accept widgets")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `a state change goes to telegram silently and is kept`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(7, null), BotOutput.Widget("<p>board</p>", "Chess", WidgetRequests.AGENT))

        tools().setWidgetState(messageId = 7, chatData = """{"turn":"b"}""")

        val call = calls.single()
        assertEquals("editMessageWidgetData", call.method)
        assertEquals(7, call.body["message_id"].asInt())
        assertEquals("""{"turn":"b"}""", call.body["chat_data"].asString())
        assertTrue(call.body["public_data"] == null)
        assertEquals("""{"turn":"b"}""", registry.find(-100L, 7)?.chatData)
    }

    @Test
    fun `a tally's counts cannot be overwritten`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(7, null), BotOutput.Widget("<p>poll</p>", "Poll", WidgetRequests.TALLY))

        val failure = toolFailure { tools().setWidgetState(messageId = 7, publicData = "{}") }

        assertContains(failure, "only presses change")
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a widget from another chat is not this turn's to change`() = runBlocking {
        registry.remember(ChatTarget(-200L), SentWidget(7, null), BotOutput.Widget("<p>board</p>", "Chess", WidgetRequests.AGENT))

        val failure = toolFailure { tools().setWidgetState(messageId = 7, chatData = "{}") }

        assertContains(failure, "No widget of yours")
    }

    @Test
    fun `only a shared widget can be posted again`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(7, null), BotOutput.Widget("<p>a</p>", "Static", WidgetRequests.NONE))
        registry.remember(ChatTarget(-100L), SentWidget(8, "88"), BotOutput.Widget("<p>b</p>", "Shared", WidgetRequests.TALLY, shared = true))
        val outbox = BotOutbox()

        assertContains(toolFailure { tools(outbox).sendWidgetAgain(messageId = 7) }, "not shared")

        tools(outbox).sendWidgetAgain(messageId = 8)

        val again = assertIs<BotOutput.SharedWidget>(outbox.pending.single().output)
        assertEquals("88", again.widgetId)
        assertEquals("Shared", again.caption)
    }

    @Test
    fun `the list names each widget with how its presses are handled`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(8, "88"), BotOutput.Widget("<p>b</p>", "Lunch poll", WidgetRequests.TALLY, shared = true))

        val list = tools().listWidgets()

        assertContains(list, "message 8: \"Lunch poll\", requests=`tally`, shared")
    }

    @Test
    fun `the tools read as schemas, with only the html and caption required to send`() {
        val functions = tools().toolFunctions().associateBy { it.name }

        assertEquals(setOf("listWidgets", "sendWidget", "sendWidgetAgain", "setWidgetState", "widgetGuide"), functions.keys)
        assertEquals(listOf("html", "caption"), functions.getValue("sendWidget").requiredParameters)
        assertEquals(listOf("messageId"), functions.getValue("setWidgetState").requiredParameters)
        assertTrue(functions.getValue("listWidgets").readOnly)
    }

    @Test
    fun `the guide is served`() = runBlocking {
        assertContains(tools().widgetGuide(), "PRESSES THAT REACH THE BOT")
    }

    // python's zlib.compress(html, 9), which telegram's server counts the same way
    @Test
    fun `the compressed size is the one telegram measures`() {
        val html = "<p>" + (0 until 200).joinToString(", ") { "item $it" } + "</p>"

        assertEquals(416, zlibSize(html.toByteArray()))
    }
}
